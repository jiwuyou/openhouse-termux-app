package com.termux.app.activities;

import android.content.res.ColorStateList;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.OpenCodeCdpBridge;
import com.termux.app.OpenCodeSettings;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MaintenanceCenterActivity extends AppCompatActivity {

    private static final String LOG_TAG = "MaintenanceCenter";
    private static final int LOG_CHAR_LIMIT = 24000;
    private static final Pattern DONE_PATTERN = Pattern.compile("__TERMUX_MAINT_DONE__:([a-zA-Z0-9_-]+):(\\d+)");

    private TextView statusHeadlineView;
    private TextView statusBodyView;
    private TextView currentStageView;
    private TextView liveLogView;
    private TextView helpBodyView;
    private TextView terminalStatusView;
    private Button configureDefaultPortButton;
    private Button customPortButton;
    private Button viewFullLogButton;
    private Button openBrowserButton;
    private FrameLayout terminalContainer;
    private TerminalView terminalView;

    private TermuxSession maintenanceSession;
    private String currentStageSlug;
    private String currentStageLabel;
    private String lastHandledMarker;
    private StageAction pendingStageAction;
    private boolean commandInFlight;
    private boolean maintenanceSessionInitPosted;
    private String terminalFailureMessage;
    private Boolean opencodeReachable;
    private boolean stageStatusCheckInFlight;
    private boolean stageStatusCheckQueued;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final EnumMap<StageAction, Button> stageButtons = new EnumMap<>(StageAction.class);
    private final EnumMap<StageAction, StagePresentation> stagePresentations = new EnumMap<>(StageAction.class);

    private final MaintenanceTerminalSessionClient terminalSessionClient = new MaintenanceTerminalSessionClient();
    private final MaintenanceTerminalViewClient terminalViewClient = new MaintenanceTerminalViewClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maintenance_center);

        statusHeadlineView = findViewById(R.id.statusHeadline);
        statusBodyView = findViewById(R.id.statusBody);
        currentStageView = findViewById(R.id.currentStage);
        liveLogView = findViewById(R.id.liveLog);
        helpBodyView = findViewById(R.id.helpBody);
        terminalStatusView = findViewById(R.id.embeddedTerminalStatus);
        configureDefaultPortButton = findViewById(R.id.buttonConfigureDefaultPort);
        customPortButton = findViewById(R.id.buttonStartCustomPort);
        viewFullLogButton = findViewById(R.id.buttonViewFullLog);
        openBrowserButton = findViewById(R.id.buttonOpenBrowser);
        terminalContainer = findViewById(R.id.maintenanceTerminalContainer);

        helpBodyView.setText(getString(R.string.help_body));
        currentStageView.setText(R.string.current_stage_placeholder);
        liveLogView.setText(R.string.result_placeholder);

        bindStageButtons();
        initializeStagePresentations();
        configureDefaultPortButton.setOnClickListener(v -> showDefaultPortDialog());
        viewFullLogButton.setOnClickListener(v -> openFullLog());
        updateLogButtonState();
        refreshStatus();
        requestStageStatusRefresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleMaintenanceSessionInit();
        refreshStatus();
        requestStageStatusRefresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        maintenanceSessionInitPosted = false;
        backgroundExecutor.shutdownNow();
        try {
            if (maintenanceSession != null && maintenanceSession.getTerminalSession() != null) {
                maintenanceSession.getTerminalSession().finishIfRunning();
                maintenanceSession = null;
            }
        } catch (Throwable throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to finish maintenance terminal session on destroy", throwable);
        }
    }

    private void bindStageButtons() {
        bindStageButton(StageAction.PREPARE, R.id.buttonPrepare);
        bindStageButton(StageAction.TERMUX_PACKAGES, R.id.buttonTermuxPackages);
        bindStageButton(StageAction.INSTALL_UBUNTU, R.id.buttonInstallUbuntu);
        bindStageButton(StageAction.UBUNTU_PACKAGES, R.id.buttonUbuntuPackages);
        bindStageButton(StageAction.INSTALL_OPENCODE, R.id.buttonInstallOpenCode);
        bindStageButton(StageAction.START, R.id.buttonStart);
        bindStageButton(StageAction.RESTART, R.id.buttonRestart);
        customPortButton.setOnClickListener(v -> showCustomPortDialog());
        openBrowserButton.setOnClickListener(v -> openBrowser());
    }

    private void bindStageButton(StageAction stageAction, int buttonId) {
        Button button = findViewById(buttonId);
        stageButtons.put(stageAction, button);
        button.setOnClickListener(v -> runStage(stageAction));
    }

    private void initializeStagePresentations() {
        for (StageAction stageAction : StageAction.values()) {
            stagePresentations.put(stageAction, StagePresentation.checking(this));
        }
        applyStagePresentations();
    }

    private void scheduleMaintenanceSessionInit() {
        if (maintenanceSessionInitPosted) return;
        maintenanceSessionInitPosted = true;
        terminalContainer.post(() -> {
            maintenanceSessionInitPosted = false;
            if (isFinishing() || isDestroyed()) return;
            ensureMaintenanceSession();
            refreshStatus();
        });
    }

    private void ensureMaintenanceSession() {
        try {
            ensureTerminalViewCreated();
            ensureMaintenanceSessionLocked();
            terminalFailureMessage = null;
        } catch (Throwable throwable) {
            terminalFailureMessage = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            maintenanceSession = null;
            terminalStatusView.setText(R.string.embedded_terminal_status_failed);
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to initialize maintenance terminal session", throwable);
        }
    }

    private void ensureTerminalViewCreated() {
        if (terminalView != null) return;
        terminalView = new TerminalView(this, null);
        terminalView.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        terminalView.setFocusableInTouchMode(true);
        terminalView.setVerticalScrollBarEnabled(true);
        terminalView.setTerminalViewClient(terminalViewClient);
        terminalView.setTextSize(14);
        terminalContainer.removeAllViews();
        terminalContainer.addView(terminalView);
    }

    private void ensureMaintenanceSessionLocked() {
        if (maintenanceSession != null && maintenanceSession.getTerminalSession() != null
            && maintenanceSession.getTerminalSession().isRunning()) {
            if (terminalView.getCurrentSession() != maintenanceSession.getTerminalSession()) {
                terminalView.attachSession(maintenanceSession.getTerminalSession());
            }
            terminalStatusView.setText(R.string.embedded_terminal_status_ready);
            return;
        }

        terminalStatusView.setText(R.string.embedded_terminal_status_starting);
        ExecutionCommand command = new ExecutionCommand(
            TermuxShellManager.getNextShellId(),
            null,
            null,
            null,
            TermuxConstants.TERMUX_HOME_DIR_PATH,
            ExecutionCommand.Runner.TERMINAL_SESSION.getName(),
            false
        );
        command.shellName = "maintainer";
        command.commandLabel = "Maintainer Terminal";
        command.setShellCommandShellEnvironment = true;

        maintenanceSession = TermuxSession.execute(
            this,
            command,
            terminalSessionClient,
            terminalSessionClient,
            new TermuxShellEnvironment(),
            null,
            false
        );

        if (maintenanceSession != null) {
            terminalView.attachSession(maintenanceSession.getTerminalSession());
            terminalStatusView.setText(R.string.embedded_terminal_status_ready);
        } else {
            terminalStatusView.setText(R.string.embedded_terminal_status_failed);
        }
    }

    private void runStage(StageAction stageAction) {
        runStage(stageAction, false);
    }

    private void runStage(StageAction stageAction, boolean skipPreflightRefresh) {
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!skipPreflightRefresh && stageAction.shouldRefreshBeforeRun()) {
            pendingStageAction = stageAction;
            currentStageView.setText("刷新状态后执行：" + stageAction.label(this));
            requestStageStatusRefresh();
            return;
        }

        pendingStageAction = null;
        ensureMaintenanceSession();
        if (maintenanceSession == null || maintenanceSession.getTerminalSession() == null
            || !maintenanceSession.getTerminalSession().isRunning()) {
            Toast.makeText(this, R.string.status_terminal_failed, Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }

        currentStageSlug = stageAction.slug;
        currentStageLabel = stageAction.label(this);
        currentStageView.setText(currentStageLabel);
        commandInFlight = true;
        lastHandledMarker = null;
        terminalStatusView.setText(R.string.embedded_terminal_status_busy);
        liveLogView.setText(getString(R.string.result_placeholder));
        stagePresentations.put(stageAction, StagePresentation.running(this));
        applyStagePresentations();
        updateLogButtonState();
        refreshStatus();

        try {
            String command = buildStageExecutionCommand(stageAction);
            maintenanceSession.getTerminalSession().write(command);
            if (!command.endsWith("\n")) {
                maintenanceSession.getTerminalSession().write("\n");
            }
        } catch (IOException e) {
            commandInFlight = false;
            terminalStatusView.setText(R.string.embedded_terminal_status_ready);
            liveLogView.setText(getString(R.string.full_log_error, e.getMessage()));
            refreshStatus();
        }
    }

    private String buildStageExecutionCommand(StageAction stageAction) throws IOException {
        return buildAssetExecutionCommand(stageAction.label(this), stageAction.slug, stageAction.assetName, getDefaultOpenCodePort());
    }

    private String buildAssetExecutionCommand(String stageLabel, String stageSlug, String assetName, int port) throws IOException {
        String scriptBody = loadAsset(assetName)
            .replace("__PORT__", Integer.toString(port));
        String wrapperScript = buildWrapperScript(stageLabel, stageSlug, scriptBody);
        String tempScriptPath = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.maintainer-logs/run-" + stageSlug + ".sh";

        StringBuilder builder = new StringBuilder();
        builder.append("mkdir -p ").append(shellQuote(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.maintainer-logs")).append('\n');
        builder.append("cat > ").append(shellQuote(tempScriptPath)).append(" <<'__TERMUX_MAINT__'\n");
        builder.append(wrapperScript);
        if (!wrapperScript.endsWith("\n")) {
            builder.append('\n');
        }
        builder.append("__TERMUX_MAINT__\n");
        builder.append("/data/data/com.termux/files/usr/bin/bash ").append(shellQuote(tempScriptPath)).append('\n');
        builder.append("rm -f ").append(shellQuote(tempScriptPath)).append('\n');
        return builder.toString();
    }

    private void showCustomPortDialog() {
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(getString(R.string.custom_port_dialog_hint));
        input.setText(Integer.toString(getDefaultOpenCodePort()));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
            .setTitle(R.string.custom_port_dialog_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String value = input.getText() == null ? "" : input.getText().toString().trim();
                int port;
                try {
                    port = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    Toast.makeText(this, R.string.custom_port_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (port < 1 || port > 65535) {
                    Toast.makeText(this, R.string.custom_port_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                runCustomPortStart(port);
            })
            .show();
    }

    private void showDefaultPortDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(getString(R.string.custom_port_dialog_hint));
        input.setText(Integer.toString(getDefaultOpenCodePort()));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
            .setTitle(R.string.button_configure_default_port)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String value = input.getText() == null ? "" : input.getText().toString().trim();
                int port;
                try {
                    port = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    Toast.makeText(this, R.string.custom_port_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!OpenCodeSettings.isValidPort(port)) {
                    Toast.makeText(this, R.string.custom_port_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }

                OpenCodeSettings.setDefaultPort(this, port);
                Toast.makeText(this, getString(R.string.default_port_saved, port), Toast.LENGTH_SHORT).show();
                refreshStatus();
                requestStageStatusRefresh();
            })
            .show();
    }

    private void runCustomPortStart(int port) {
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        ensureMaintenanceSession();
        if (maintenanceSession == null || maintenanceSession.getTerminalSession() == null
            || !maintenanceSession.getTerminalSession().isRunning()) {
            Toast.makeText(this, R.string.status_terminal_failed, Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }

        currentStageSlug = "start_port_" + port;
        currentStageLabel = getString(R.string.custom_port_stage_label, port);
        commandInFlight = true;
        lastHandledMarker = null;
        terminalStatusView.setText(R.string.embedded_terminal_status_busy);
        liveLogView.setText(getString(R.string.result_placeholder));
        updateLogButtonState();
        refreshStatus();

        try {
            String command = buildAssetExecutionCommand(currentStageLabel, currentStageSlug, "start-opencode.sh", port);
            maintenanceSession.getTerminalSession().write(command);
            if (!command.endsWith("\n")) {
                maintenanceSession.getTerminalSession().write("\n");
            }
        } catch (IOException e) {
            commandInFlight = false;
            terminalStatusView.setText(R.string.embedded_terminal_status_ready);
            liveLogView.setText(getString(R.string.full_log_error, e.getMessage()));
            refreshStatus();
        }
    }

    private String buildWrapperScript(String stageLabel, String stageSlug, String scriptBody) {
        StringBuilder builder = new StringBuilder();
        builder.append("#!/data/data/com.termux/files/usr/bin/bash\n");
        builder.append("set -euo pipefail\n");
        builder.append("export HOME=\"${HOME:-/data/data/com.termux/files/home}\"\n");
        builder.append("export TERM=\"xterm-256color\"\n");
        builder.append("STAGE_NAME=").append(shellQuote(stageLabel)).append('\n');
        builder.append("STAGE_SLUG=").append(shellQuote(stageSlug)).append('\n');
        builder.append("LOG_DIR=\"$HOME/.maintainer-logs\"\n");
        builder.append("LOG_FILE=\"$LOG_DIR/$STAGE_SLUG.log\"\n");
        builder.append("mkdir -p \"$LOG_DIR\"\n");
        builder.append(": > \"$LOG_FILE\"\n");
        builder.append("log(){ printf '%s\\n' \"$1\" | tee -a \"$LOG_FILE\"; }\n");
        builder.append("run_logged(){ local status=0; set +e; \"$@\" 2>&1 | tee -a \"$LOG_FILE\"; status=${PIPESTATUS[0]}; set -e; return \"$status\"; }\n");
        builder.append("require_ubuntu(){ if ! command -v proot-distro >/dev/null 2>&1; then log '缺少 proot-distro，请先执行“更新 Termux 软件包”。'; exit 2; fi; if ! proot-distro login ubuntu -- true >/dev/null 2>&1; then log 'Ubuntu 尚未安装，请先执行“下载 Ubuntu”。'; exit 3; fi; }\n");
        builder.append("__maint_finish(){ local exit_code=$?; printf '__TERMUX_MAINT_DONE__:%s:%s\\n' \"$STAGE_SLUG\" \"$exit_code\" | tee -a \"$LOG_FILE\"; }\n");
        builder.append("trap __maint_finish EXIT\n");
        builder.append("log \"==> $STAGE_NAME\"\n");
        builder.append(scriptBody).append('\n');
        return builder.toString();
    }

    private void openFullLog() {
        if (currentStageSlug == null || currentStageSlug.isEmpty() || !MaintainerLogStore.hasLog(currentStageSlug)) {
            Toast.makeText(this, R.string.full_log_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, MaintenanceLogActivity.class);
        intent.putExtra(MaintenanceLogActivity.EXTRA_STAGE_SLUG, currentStageSlug);
        intent.putExtra(MaintenanceLogActivity.EXTRA_STAGE_LABEL, currentStageLabel);
        ActivityUtils.startActivity(this, intent);
    }

    private void openBrowser() {
        String url = getOpenCodeUrl();
        backgroundExecutor.execute(() -> {
            boolean openedViaCdp = OpenCodeCdpBridge.isCdpActive() && OpenCodeCdpBridge.openTab(url);
            runOnUiThread(() -> {
                if (openedViaCdp) {
                    Toast.makeText(this, R.string.quick_launch_browser_tab, Toast.LENGTH_SHORT).show();
                    return;
                }

                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    Toast.makeText(this, R.string.quick_launch_browser_fallback, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open OpenCode browser URL", e);
                    Toast.makeText(this, getString(R.string.full_log_error, e.getMessage()), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void refreshStatus() {
        boolean terminalReady = maintenanceSession != null
            && maintenanceSession.getTerminalSession() != null
            && maintenanceSession.getTerminalSession().isRunning();

        if (commandInFlight) {
            statusHeadlineView.setText(R.string.status_running_title);
        } else if (terminalReady) {
            statusHeadlineView.setText(R.string.status_ready_title);
        } else {
            statusHeadlineView.setText(R.string.status_terminal_failed);
        }

        StringBuilder body = new StringBuilder();
        if (terminalReady) {
        body.append("维护终端：").append(getString(R.string.status_terminal_ready)).append('\n');
        } else if (terminalFailureMessage != null && !terminalFailureMessage.isEmpty()) {
            body.append("维护终端：").append(getString(R.string.status_terminal_failed)).append('\n');
            body.append("失败原因：").append(terminalFailureMessage).append('\n');
        } else {
            body.append("维护终端：").append(getString(R.string.status_terminal_starting)).append('\n');
        }
        body.append("阶段执行：").append(commandInFlight ? "进行中" : "空闲").append('\n');
        body.append("OpenCode 端点：").append(getOpenCodeStatusText()).append('\n');
        body.append(getString(R.string.default_port_label, getDefaultOpenCodePort())).append('\n');
        body.append(getString(R.string.default_browser_label, getOpenCodeUrl())).append('\n');
        body.append("产品文档：").append(TermuxConstants.TERMUX_HOME_DIR_PATH).append("/product-docs").append('\n');
        body.append("工作区：").append(TermuxConstants.TERMUX_HOME_DIR_PATH).append("/workspace").append('\n');
        body.append("阶段校验：").append(getStageOverviewText());
        statusBodyView.setText(body.toString());
        updateCurrentStageSummary();
        updateOpenBrowserButtonState();
    }

    private String getOpenCodeStatusText() {
        if (opencodeReachable == null) {
            return "检测中";
        }
        return opencodeReachable ? "可访问" : "不可访问";
    }

    private String getStageOverviewText() {
        if (stagePresentations.isEmpty()) {
            return "检测中";
        }

        int completeCount = 0;
        int failedCount = 0;
        int blockedCount = 0;
        int runningCount = 0;
        int checkingCount = 0;
        for (StagePresentation presentation : stagePresentations.values()) {
            if (presentation == null) continue;
            switch (presentation.state) {
                case COMPLETE:
                    completeCount++;
                    break;
                case FAILED:
                    failedCount++;
                    break;
                case BLOCKED:
                    blockedCount++;
                    break;
                case RUNNING:
                    runningCount++;
                    break;
                case CHECKING:
                    checkingCount++;
                    break;
                case READY:
                default:
                    break;
            }
        }

        if (checkingCount == StageAction.values().length) {
            return "检测中";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("已完成 ").append(completeCount).append('/').append(StageAction.values().length);
        if (runningCount > 0) builder.append("，执行中 ").append(runningCount);
        if (blockedCount > 0) builder.append("，待前置 ").append(blockedCount);
        if (failedCount > 0) builder.append("，需修复 ").append(failedCount);
        if (checkingCount > 0) builder.append("，检测中 ").append(checkingCount);
        return builder.toString();
    }

    private void updateCurrentStageSummary() {
        if (commandInFlight && currentStageLabel != null) {
            currentStageView.setText("执行中：" + currentStageLabel);
            return;
        }

        if (currentStageSlug != null && !currentStageSlug.isEmpty()) {
            StageAction currentStageAction = StageAction.fromSlug(currentStageSlug);
            if (currentStageAction != null) {
                StagePresentation presentation = stagePresentations.get(currentStageAction);
                if (presentation != null) {
                    currentStageView.setText(presentation.headline(this, currentStageAction));
                    return;
                }
            }

            Integer exitCode = readLastExitCode(currentStageSlug);
            if (currentStageLabel != null && exitCode != null) {
                currentStageView.setText((exitCode == 0 ? "已完成：" : "失败：") + currentStageLabel);
                return;
            }
            if (currentStageLabel != null) {
                currentStageView.setText(currentStageLabel);
                return;
            }
        }

        currentStageView.setText(getString(R.string.current_stage_placeholder));
    }

    private void updateOpenBrowserButtonState() {
        if (openBrowserButton == null) return;
        int backgroundColor;
        int textColor;
        if (Boolean.TRUE.equals(opencodeReachable)) {
            backgroundColor = ContextCompat.getColor(this, R.color.stageComplete);
            textColor = ContextCompat.getColor(this, R.color.stageOnDark);
            openBrowserButton.setEnabled(true);
        } else if (Boolean.FALSE.equals(opencodeReachable)) {
            backgroundColor = ContextCompat.getColor(this, R.color.stageBlocked);
            textColor = ContextCompat.getColor(this, R.color.stageBlockedText);
            openBrowserButton.setEnabled(false);
        } else {
            backgroundColor = ContextCompat.getColor(this, R.color.stageChecking);
            textColor = ContextCompat.getColor(this, R.color.stageCheckingText);
            openBrowserButton.setEnabled(false);
        }
        openBrowserButton.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
        openBrowserButton.setTextColor(textColor);
    }

    private void requestStageStatusRefresh() {
        if (backgroundExecutor.isShutdown()) return;
        if (stageStatusCheckInFlight) {
            stageStatusCheckQueued = true;
            return;
        }

        stageStatusCheckInFlight = true;
        backgroundExecutor.execute(() -> {
            StageCheckSnapshot snapshot = inspectStageStatuses();
            runOnUiThread(() -> {
                stageStatusCheckInFlight = false;
                if (isFinishing() || isDestroyed()) return;
                opencodeReachable = snapshot.opencodeReachable;
                for (Map.Entry<StageAction, StagePresentation> entry : snapshot.presentations.entrySet()) {
                    stagePresentations.put(entry.getKey(), entry.getValue());
                }
                if (commandInFlight && currentStageSlug != null) {
                    StageAction runningStage = StageAction.fromSlug(currentStageSlug);
                    if (runningStage != null) {
                        stagePresentations.put(runningStage, StagePresentation.running(this));
                    }
                }
                applyStagePresentations();
                refreshStatus();
                if (!commandInFlight && pendingStageAction != null) {
                    StageAction stageAction = pendingStageAction;
                    pendingStageAction = null;
                    runStage(stageAction, true);
                    return;
                }
                if (stageStatusCheckQueued) {
                    stageStatusCheckQueued = false;
                    requestStageStatusRefresh();
                }
            });
        });
    }

    private void applyStagePresentations() {
        if (configureDefaultPortButton != null) {
            configureDefaultPortButton.setText(getString(R.string.button_configure_default_port_with_value, getDefaultOpenCodePort()));
            configureDefaultPortButton.setEnabled(!commandInFlight);
            configureDefaultPortButton.setAlpha(configureDefaultPortButton.isEnabled() ? 1.0f : 0.78f);
        }

        for (StageAction stageAction : StageAction.values()) {
            Button button = stageButtons.get(stageAction);
            StagePresentation presentation = stagePresentations.get(stageAction);
            if (button == null || presentation == null) continue;

            button.setText(presentation.buttonText(this, stageAction));
            button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, presentation.backgroundColorRes)));
            button.setTextColor(ContextCompat.getColor(this, presentation.textColorRes));
            button.setEnabled(!commandInFlight && presentation.state != StageUiState.CHECKING && presentation.state != StageUiState.BLOCKED);
            button.setAlpha(button.isEnabled() ? 1.0f : 0.78f);
        }

        if (customPortButton != null) {
            customPortButton.setEnabled(!commandInFlight);
            customPortButton.setAlpha(customPortButton.isEnabled() ? 1.0f : 0.78f);
        }
    }

    private StageCheckSnapshot inspectStageStatuses() {
        StageCheckSnapshot snapshot = new StageCheckSnapshot();

        boolean prepareComplete = isPrepareStageComplete();
        boolean termuxPackagesComplete = isTermuxPackagesStageComplete();
        boolean ubuntuInstalled = termuxPackagesComplete && isUbuntuInstalled();
        boolean ubuntuPackagesComplete = ubuntuInstalled && isUbuntuPackagesStageComplete();
        boolean openCodeInstalled = ubuntuInstalled && isOpenCodeInstalled();
        boolean openCodeRunning = openCodeInstalled && isOpenCodeWebReachable();

        snapshot.opencodeReachable = openCodeRunning;

        Integer prepareExitCode = readLastExitCode(StageAction.PREPARE);
        Integer termuxPackagesExitCode = readLastExitCode(StageAction.TERMUX_PACKAGES);
        Integer installUbuntuExitCode = readLastExitCode(StageAction.INSTALL_UBUNTU);
        Integer ubuntuPackagesExitCode = readLastExitCode(StageAction.UBUNTU_PACKAGES);
        Integer installOpenCodeExitCode = readLastExitCode(StageAction.INSTALL_OPENCODE);
        Integer startExitCode = readLastExitCode(StageAction.START);

        snapshot.presentations.put(
            StageAction.PREPARE,
            prepareComplete
                ? StagePresentation.complete(this, getString(R.string.stage_detail_prepare_complete))
                : failedOrReady(prepareExitCode,
                    getString(R.string.stage_detail_prepare_failed),
                    getString(R.string.stage_detail_prepare_ready))
        );

        snapshot.presentations.put(
            StageAction.TERMUX_PACKAGES,
            termuxPackagesComplete
                ? StagePresentation.complete(this, getString(R.string.stage_detail_termux_packages_complete))
                : failedOrReady(termuxPackagesExitCode,
                    getString(R.string.stage_detail_termux_packages_failed),
                    getString(R.string.stage_detail_termux_packages_ready))
        );

        snapshot.presentations.put(
            StageAction.INSTALL_UBUNTU,
            ubuntuInstalled
                ? StagePresentation.complete(this, getString(R.string.stage_detail_install_ubuntu_complete))
                : (!termuxPackagesComplete
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_install_ubuntu_blocked))
                    : failedOrReady(installUbuntuExitCode,
                        getString(R.string.stage_detail_install_ubuntu_failed),
                        getString(R.string.stage_detail_install_ubuntu_ready)))
        );

        snapshot.presentations.put(
            StageAction.UBUNTU_PACKAGES,
            ubuntuPackagesComplete
                ? StagePresentation.complete(this, getString(R.string.stage_detail_ubuntu_packages_complete))
                : (!ubuntuInstalled
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_ubuntu_packages_blocked))
                    : failedOrReady(ubuntuPackagesExitCode,
                        getString(R.string.stage_detail_ubuntu_packages_failed),
                        getString(R.string.stage_detail_ubuntu_packages_ready)))
        );

        snapshot.presentations.put(
            StageAction.INSTALL_OPENCODE,
            openCodeInstalled
                ? StagePresentation.complete(this, getString(R.string.stage_detail_install_opencode_complete))
                : (!ubuntuPackagesComplete
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_install_opencode_blocked))
                    : failedOrReady(installOpenCodeExitCode,
                        getString(R.string.stage_detail_install_opencode_failed),
                        getString(R.string.stage_detail_install_opencode_ready)))
        );

        snapshot.presentations.put(
            StageAction.START,
            openCodeRunning
                ? StagePresentation.complete(this, getString(R.string.stage_detail_start_complete))
                : (!openCodeInstalled
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_start_blocked))
                    : failedOrReady(startExitCode,
                        getString(R.string.stage_detail_start_failed),
                        getString(R.string.stage_detail_start_ready)))
        );

        snapshot.presentations.put(
            StageAction.RESTART,
            !openCodeInstalled
                ? StagePresentation.blocked(this, getString(R.string.stage_detail_restart_blocked))
                : (openCodeRunning
                    ? StagePresentation.ready(this, getString(R.string.stage_detail_restart_ready_running))
                    : StagePresentation.ready(this, getString(R.string.stage_detail_restart_ready_stopped)))
        );

        return snapshot;
    }

    private StagePresentation failedOrReady(Integer exitCode, String failedDetail, String readyDetail) {
        if (exitCode != null && exitCode != 0) {
            return StagePresentation.failed(this, failedDetail);
        }
        return StagePresentation.ready(this, readyDetail);
    }

    private boolean isPrepareStageComplete() {
        File docsDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "product-docs");
        File workspaceDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "workspace");
        File readmeFile = new File(docsDir, "README.md");
        File userGuideFile = new File(docsDir, "USER_GUIDE.md");
        File aiGuideFile = new File(docsDir, "AI_GUIDE.md");
        File propertiesFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);

        return docsDir.isDirectory()
            && workspaceDir.isDirectory()
            && readmeFile.isFile()
            && userGuideFile.isFile()
            && aiGuideFile.isFile()
            && propertyContainsAllowExternalApps(propertiesFile);
    }

    private boolean propertyContainsAllowExternalApps(File propertiesFile) {
        if (!propertiesFile.isFile()) return false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new java.io.FileInputStream(propertiesFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = line.replace(" ", "");
                if (normalized.startsWith(TermuxConstants.PROP_ALLOW_EXTERNAL_APPS + "=")) {
                    return normalized.endsWith("=true");
                }
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read termux.properties for maintenance verification", e);
        }

        return false;
    }

    private boolean isTermuxPackagesStageComplete() {
        return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro").canExecute()
            && new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "curl").canExecute();
    }

    private boolean isUbuntuInstalled() {
        if (!isTermuxPackagesStageComplete()) return false;
        return runTermuxCommand("proot-distro login ubuntu -- true >/dev/null 2>&1").isSuccess();
    }

    private boolean isUbuntuPackagesStageComplete() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'command -v curl >/dev/null 2>&1 && command -v git >/dev/null 2>&1 && command -v ps >/dev/null 2>&1 && test -e /etc/ssl/certs/ca-certificates.crt'"
        ).isSuccess();
    }

    private boolean isOpenCodeInstalled() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'export PATH=\"$HOME/.opencode/bin:$HOME/.local/bin:$PATH\"; (command -v opencode >/dev/null 2>&1 || test -x \"$HOME/.opencode/bin/opencode\") && test -f \"$HOME/product-links/docs-path.txt\" && test -f \"$HOME/product-links/workspace-path.txt\"'"
        ).isSuccess();
    }

    private ShellCheckResult runTermuxCommand(String command) {
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
                "-lc",
                command
            );
            processBuilder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            processBuilder.redirectErrorStream(true);
            Map<String, String> environment = processBuilder.environment();
            environment.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            environment.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            environment.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
            environment.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            environment.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            environment.put("LANG", "C.UTF-8");

            process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < 600) {
                        if (output.length() > 0) output.append('\n');
                        output.append(line);
                    }
                }
            }

            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ShellCheckResult(124, output.toString());
            }

            return new ShellCheckResult(process.exitValue(), output.toString());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run maintenance verification command", e);
            return new ShellCheckResult(1, e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private Integer readLastExitCode(StageAction stageAction) {
        return readLastExitCode(stageAction.slug);
    }

    private Integer readLastExitCode(String stageSlug) {
        try {
            String content = MaintainerLogStore.readLog(stageSlug);
            Matcher matcher = DONE_PATTERN.matcher(content);
            Integer exitCode = null;
            while (matcher.find()) {
                exitCode = Integer.parseInt(matcher.group(2));
            }
            return exitCode;
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read maintenance stage log for verification", e);
            return null;
        }
    }

    private boolean isOpenCodeWebReachable() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'curl -fsS --max-time 3 http://127.0.0.1:" + getDefaultOpenCodePort() + "/ >/dev/null 2>&1'"
        ).isSuccess();
    }

    private int getDefaultOpenCodePort() {
        return OpenCodeSettings.getDefaultPort(this);
    }

    private String getOpenCodeUrl() {
        return OpenCodeSettings.getDefaultLoopbackUrl(this);
    }

    private void updateLogButtonState() {
        viewFullLogButton.setEnabled(currentStageSlug != null && MaintainerLogStore.hasLog(currentStageSlug));
    }

    private void refreshLiveLog() {
        if (currentStageSlug == null || currentStageSlug.isEmpty()) {
            liveLogView.setText(R.string.result_placeholder);
            return;
        }

        try {
            String content = MaintainerLogStore.readTail(currentStageSlug, LOG_CHAR_LIMIT);
            liveLogView.setText(content.isEmpty() ? getString(R.string.result_placeholder) : content);
        } catch (IOException e) {
            liveLogView.setText(getString(R.string.full_log_error, e.getMessage()));
        }
        updateLogButtonState();
    }

    private void inspectTranscriptForCompletion(TerminalSession terminalSession) {
        String transcript = ShellUtils.getTerminalSessionTranscriptText(terminalSession, false, false);
        Matcher matcher = DONE_PATTERN.matcher(transcript);
        String foundMarker = null;
        String foundSlug = null;
        int foundExitCode = 0;
        while (matcher.find()) {
            foundMarker = matcher.group(0);
            foundSlug = matcher.group(1);
            foundExitCode = Integer.parseInt(matcher.group(2));
        }

        if (foundMarker == null || foundMarker.equals(lastHandledMarker)) {
            return;
        }

        lastHandledMarker = foundMarker;
        if (currentStageSlug != null && currentStageSlug.equals(foundSlug)) {
            commandInFlight = false;
            currentStageView.setText((foundExitCode == 0 ? "已完成：" : "失败：") + currentStageLabel);
            terminalStatusView.setText(R.string.embedded_terminal_status_ready);
            refreshLiveLog();
            requestStageStatusRefresh();
            refreshStatus();
        }
    }

    private String loadAsset(String assetName) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (InputStream inputStream = getAssets().open("maintainer/" + assetName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private final class MaintenanceTerminalViewClient extends TermuxTerminalViewClientBase {
        @Override
        public void onSingleTapUp(MotionEvent e) {
            terminalView.requestFocus();
            KeyboardUtils.showSoftKeyboard(MaintenanceCenterActivity.this, terminalView);
        }

        @Override
        public boolean shouldEnforceCharBasedInput() {
            return false;
        }

        @Override
        public boolean onLongPress(MotionEvent event) {
            return false;
        }

        @Override
        public boolean isTerminalViewSelected() {
            return true;
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
            return false;
        }
    }

    private final class MaintenanceTerminalSessionClient extends TermuxTerminalSessionClientBase implements TermuxSession.TermuxSessionClient {
        @Override
        public void onTextChanged(TerminalSession changedSession) {
            runOnUiThread(() -> {
                try {
                    terminalView.onScreenUpdated();
                    refreshLiveLog();
                    inspectTranscriptForCompletion(changedSession);
                } catch (Throwable throwable) {
                    terminalFailureMessage = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to process maintenance terminal text update", throwable);
                    refreshStatus();
                }
            });
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
            runOnUiThread(() -> {
                try {
                    terminalView.onScreenUpdated();
                    terminalStatusView.setText(R.string.embedded_terminal_status_closed);
                    commandInFlight = false;
                    refreshLiveLog();
                    requestStageStatusRefresh();
                    refreshStatus();
                } catch (Throwable throwable) {
                    terminalFailureMessage = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to handle maintenance terminal session finish", throwable);
                    refreshStatus();
                }
            });
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
            try {
                terminalView.setTerminalCursorBlinkerState(state, false);
            } catch (Throwable throwable) {
                terminalFailureMessage = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to update maintenance terminal cursor state", throwable);
            }
        }

        @Override
        public void onColorsChanged(TerminalSession changedSession) {
            try {
                terminalView.onScreenUpdated();
            } catch (Throwable throwable) {
                terminalFailureMessage = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to update maintenance terminal colors", throwable);
            }
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return null;
        }

        @Override
        public void onTermuxSessionExited(TermuxSession termuxSession) {
            Logger.logDebug(LOG_TAG, "Maintenance terminal session exited");
        }
    }

    private enum StageUiState {
        CHECKING,
        READY,
        RUNNING,
        COMPLETE,
        FAILED,
        BLOCKED
    }

    private static final class ShellCheckResult {
        final int exitCode;
        final String output;

        ShellCheckResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        boolean isSuccess() {
            return exitCode == 0;
        }
    }

    private static final class StageCheckSnapshot {
        final EnumMap<StageAction, StagePresentation> presentations = new EnumMap<>(StageAction.class);
        Boolean opencodeReachable;
    }

    private static final class StagePresentation {
        final StageUiState state;
        final String badge;
        final String detail;
        final int backgroundColorRes;
        final int textColorRes;

        StagePresentation(StageUiState state, String badge, String detail, int backgroundColorRes, int textColorRes) {
            this.state = state;
            this.badge = badge;
            this.detail = detail;
            this.backgroundColorRes = backgroundColorRes;
            this.textColorRes = textColorRes;
        }

        static StagePresentation checking(MaintenanceCenterActivity activity) {
            return new StagePresentation(
                StageUiState.CHECKING,
                activity.getString(R.string.stage_badge_checking),
                activity.getString(R.string.stage_detail_checking),
                R.color.stageChecking,
                R.color.stageCheckingText
            );
        }

        static StagePresentation ready(MaintenanceCenterActivity activity, String detail) {
            return new StagePresentation(
                StageUiState.READY,
                activity.getString(R.string.stage_badge_ready),
                detail,
                R.color.stageReady,
                R.color.stageReadyText
            );
        }

        static StagePresentation running(MaintenanceCenterActivity activity) {
            return new StagePresentation(
                StageUiState.RUNNING,
                activity.getString(R.string.stage_badge_running),
                activity.getString(R.string.stage_detail_running),
                R.color.stageRunning,
                R.color.stageRunningText
            );
        }

        static StagePresentation complete(MaintenanceCenterActivity activity, String detail) {
            return new StagePresentation(
                StageUiState.COMPLETE,
                activity.getString(R.string.stage_badge_complete),
                detail,
                R.color.stageComplete,
                R.color.stageOnDark
            );
        }

        static StagePresentation failed(MaintenanceCenterActivity activity, String detail) {
            return new StagePresentation(
                StageUiState.FAILED,
                activity.getString(R.string.stage_badge_failed),
                detail,
                R.color.stageFailed,
                R.color.stageOnDark
            );
        }

        static StagePresentation blocked(MaintenanceCenterActivity activity, String detail) {
            return new StagePresentation(
                StageUiState.BLOCKED,
                activity.getString(R.string.stage_badge_blocked),
                detail,
                R.color.stageBlocked,
                R.color.stageBlockedText
            );
        }

        String buttonText(MaintenanceCenterActivity activity, StageAction stageAction) {
            return badge + " · " + stageAction.label(activity) + "\n" + detail;
        }

        String headline(MaintenanceCenterActivity activity, StageAction stageAction) {
            return badge + "：" + stageAction.label(activity) + "；" + detail;
        }
    }

    private enum StageAction {
        PREPARE("prepare", "prepare-product.sh"),
        TERMUX_PACKAGES("termux_packages", "update-termux-packages.sh"),
        INSTALL_UBUNTU("install_ubuntu", "install-ubuntu.sh"),
        UBUNTU_PACKAGES("ubuntu_packages", "update-ubuntu-packages.sh"),
        INSTALL_OPENCODE("install_opencode", "install-opencode.sh"),
        START("start", "start-opencode.sh"),
        RESTART("restart", "restart-opencode.sh");

        final String slug;
        final String assetName;

        StageAction(String slug, String assetName) {
            this.slug = slug;
            this.assetName = assetName;
        }

        String label(MaintenanceCenterActivity activity) {
            switch (this) {
                case PREPARE:
                    return activity.getString(R.string.button_prepare);
                case TERMUX_PACKAGES:
                    return activity.getString(R.string.button_termux_packages);
                case INSTALL_UBUNTU:
                    return activity.getString(R.string.button_install_ubuntu);
                case UBUNTU_PACKAGES:
                    return activity.getString(R.string.button_ubuntu_packages);
                case INSTALL_OPENCODE:
                    return activity.getString(R.string.button_install_opencode);
                case START:
                    return activity.getString(R.string.button_start);
                case RESTART:
                default:
                    return activity.getString(R.string.button_restart);
            }
        }

        boolean shouldRefreshBeforeRun() {
            return this == INSTALL_OPENCODE || this == START || this == RESTART;
        }

        static StageAction fromSlug(String slug) {
            for (StageAction stageAction : values()) {
                if (stageAction.slug.equals(slug)) {
                    return stageAction;
                }
            }
            return null;
        }
    }
}
