package vn.hunghd.flutterdownloader;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;

import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.flutter.app.FlutterActivity;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.PluginRegistry;

public class FlutterDownloaderPlugin implements MethodCallHandler, Application.ActivityLifecycleCallbacks {
    private static final String CHANNEL = "vn.hunghd/downloader";
    private static final String TAG = "flutter_download_task";

    private MethodChannel flutterChannel;
    private TaskDbHelper dbHelper;
    private TaskDao taskDao;
    private Context context;

    private final BroadcastReceiver updateProcessEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String id = intent.getStringExtra(DownloadWorker.EXTRA_ID);
            int progress = intent.getIntExtra(DownloadWorker.EXTRA_PROGRESS, 0);
            int status = intent.getIntExtra(DownloadWorker.EXTRA_STATUS, DownloadStatus.UNDEFINED);
            double speed = intent.getDoubleExtra(DownloadWorker.EXTRA_SPEED, 0);
            sendUpdateProgress(id, status, progress, speed);
        }
    };

    private FlutterDownloaderPlugin(Context context, BinaryMessenger messenger) {
        this.context = context;
        flutterChannel = new MethodChannel(messenger, CHANNEL);
        flutterChannel.setMethodCallHandler(this);
        dbHelper = TaskDbHelper.getInstance(context);
        taskDao = new TaskDao(dbHelper);
    }

    public static void registerWith(PluginRegistry.Registrar registrar) {
        final FlutterDownloaderPlugin plugin = new FlutterDownloaderPlugin(registrar.context(), registrar.messenger());
        registrar.activity().getApplication().registerActivityLifecycleCallbacks(plugin);
    }

    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {
        switch (call.method) {
            case "enqueue":
                enqueue(call, result);
                break;
            case "loadTasks":
                loadTasks(call, result);
                break;
            case "loadTasksWithRawQuery":
                loadTasksWithRawQuery(call, result);
                break;
            case "cancel":
                cancel(call, result);
                break;
            case "cancelAll":
                cancelAll(call, result);
                break;
            case "pause":
                pause(call, result);
                break;
            case "resume":
                resume(call, result);
                break;
            case "retry":
                retry(call, result);
                break;
            case "open":
                open(call, result);
                break;
            case "remove":
                remove(call, result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle bundle) {

    }

    @Override
    public void onActivityStarted(Activity activity) {
        if (activity instanceof FlutterActivity) {
            LocalBroadcastManager.getInstance(context).registerReceiver(updateProcessEventReceiver,
                    new IntentFilter(DownloadWorker.UPDATE_PROCESS_EVENT));
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {

    }

    @Override
    public void onActivityPaused(Activity activity) {

    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (activity instanceof FlutterActivity) {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(updateProcessEventReceiver);
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {

    }

    @Override
    public void onActivityDestroyed(Activity activity) {

    }

    private WorkRequest buildRequest(String url, String savedDir, String filename, String headers, boolean showNotification, boolean openFileFromNotification, boolean isResume, boolean requiresStorageNotLow) {
        WorkRequest request = new OneTimeWorkRequest.Builder(DownloadWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiresStorageNotLow(requiresStorageNotLow)
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.SECONDS)
                .setInputData(new Data.Builder()
                        .putString(DownloadWorker.ARG_URL, url)
                        .putString(DownloadWorker.ARG_SAVED_DIR, savedDir)
                        .putString(DownloadWorker.ARG_FILE_NAME, filename)
                        .putString(DownloadWorker.ARG_HEADERS, headers)
                        .putBoolean(DownloadWorker.ARG_SHOW_NOTIFICATION, showNotification)
                        .putBoolean(DownloadWorker.ARG_OPEN_FILE_FROM_NOTIFICATION, openFileFromNotification)
                        .putBoolean(DownloadWorker.ARG_IS_RESUME, isResume)
                        .build()
                )
                .build();
        return request;
    }

    private void sendUpdateProgress(String id, int status, int progress, double speed) {
        Map<String, Object> args = new HashMap<>();
        args.put("task_id", id);
        args.put("status", status);
        args.put("progress", progress);
        args.put("speed", speed);
        flutterChannel.invokeMethod("updateProgress", args);
    }

    private void enqueue(MethodCall call, final MethodChannel.Result result) {
        final String url = call.argument("url");
        final String savedDir = call.argument("saved_dir");
        final String filename = call.argument("file_name");
        final String headers = call.argument("headers");
        final String extra = call.argument("extra");
        final boolean showNotification = call.argument("show_notification");
        final boolean openFileFromNotification = call.argument("open_file_from_notification");
        boolean requiresStorageNotLow = call.argument("requires_storage_not_low");

        WorkRequest request = buildRequest(url, savedDir, filename, headers, showNotification, openFileFromNotification, false, requiresStorageNotLow);
        WorkManager.getInstance(context).enqueue(request);
        String taskId = request.getId().toString();
        result.success(taskId);
        sendUpdateProgress(taskId, DownloadStatus.ENQUEUED, 0, 0);
        taskDao.insertOrUpdateNewTask(taskId, url, DownloadStatus.ENQUEUED, 0, filename, savedDir, headers, extra, showNotification, openFileFromNotification);
    }

    private void loadTasks(MethodCall call, MethodChannel.Result result) {
        List<DownloadTask> tasks = taskDao.loadAllTasks();
        List<Map> array = new ArrayList<>();
        for (DownloadTask task : tasks) {
            Map<String, Object> item = new HashMap<>();
            item.put("task_id", task.taskId);
            item.put("status", task.status);
            item.put("progress", task.progress);
            item.put("url", task.url);
            item.put("extra", task.extra);
            item.put("file_name", task.filename);
            item.put("saved_dir", task.savedDir);
            item.put("time_created", task.timeCreated);
            array.add(item);
        }
        result.success(array);
    }

    private void loadTasksWithRawQuery(MethodCall call, MethodChannel.Result result) {
        String query = call.argument("query");
        List<DownloadTask> tasks = taskDao.loadTasksWithRawQuery(query);
        List<Map> array = new ArrayList<>();
        for (DownloadTask task : tasks) {
            Map<String, Object> item = new HashMap<>();
            item.put("task_id", task.taskId);
            item.put("status", task.status);
            item.put("progress", task.progress);
            item.put("url", task.url);
            item.put("extra", task.extra);
            item.put("file_name", task.filename);
            item.put("saved_dir", task.savedDir);
            item.put("time_created", task.timeCreated);
            array.add(item);
        }
        result.success(array);
    }

    private void cancel(MethodCall call, MethodChannel.Result result) {
        String taskId = call.argument("task_id");
        WorkManager.getInstance(context).cancelWorkById(UUID.fromString(taskId));
        result.success(null);
    }

    private void cancelAll(MethodCall call, MethodChannel.Result result) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG);
        result.success(null);
    }

    private void pause(MethodCall call, MethodChannel.Result result) {
        String taskId = call.argument("task_id");
        taskDao.updateTask(taskId, true);
        WorkManager.getInstance(context).cancelWorkById(UUID.fromString(taskId));
        result.success(null);
    }

    private void resume(MethodCall call, MethodChannel.Result result) {
        String taskId = call.argument("task_id");
        DownloadTask task = taskDao.loadTask(taskId);
        boolean requiresStorageNotLow = call.argument("requires_storage_not_low");
        if (task != null) {
            if (task.status == DownloadStatus.PAUSED) {
                String filename = task.filename;
                if (filename == null) {
                    filename = task.url.substring(task.url.lastIndexOf("/") + 1, task.url.length());
                }
                String partialFilePath = task.savedDir + File.separator + filename;
                File partialFile = new File(partialFilePath);
                if (partialFile.exists()) {
                    WorkRequest request = buildRequest(task.url, task.savedDir, task.filename, task.headers, task.showNotification, task.openFileFromNotification, true, requiresStorageNotLow);
                    String newTaskId = request.getId().toString();
                    result.success(newTaskId);
                    sendUpdateProgress(newTaskId, DownloadStatus.RUNNING, task.progress, 0);
                    taskDao.updateTask(taskId, newTaskId, DownloadStatus.RUNNING, task.progress, false);
                    WorkManager.getInstance(context).enqueue(request);
                } else {
                    result.error("invalid_data", "not found partial downloaded data, this task cannot be resumed", null);
                }
            } else {
                result.error("invalid_status", "only paused task can be resumed", null);
            }
        } else {
            result.error("invalid_task_id", "not found task corresponding to given task id", null);
        }
    }

    private void retry(MethodCall call, MethodChannel.Result result) {
        String taskId = call.argument("task_id");
        DownloadTask task = taskDao.loadTask(taskId);
        boolean requiresStorageNotLow = call.argument("requires_storage_not_low");
        if (task != null) {
            if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELED) {
                WorkRequest request = buildRequest(task.url, task.savedDir, task.filename, task.headers, task.showNotification, task.openFileFromNotification, false, requiresStorageNotLow);
                String newTaskId = request.getId().toString();
                result.success(newTaskId);
                sendUpdateProgress(newTaskId, DownloadStatus.ENQUEUED, task.progress, 0);
                taskDao.updateTask(taskId, newTaskId, DownloadStatus.ENQUEUED, task.progress, false);
                WorkManager.getInstance(context).enqueue(request);
            } else {
                result.error("invalid_status", "only failed and canceled task can be retried", null);
            }
        } else {
            result.error("invalid_task_id", "not found task corresponding to given task id", null);
        }
    }

    private void open(MethodCall call, MethodChannel.Result result) {
        String taskId = call.argument("task_id");
        DownloadTask task = taskDao.loadTask(taskId);
        if (task != null) {
            if (task.status == DownloadStatus.COMPLETE) {
                String fileURL = task.url;
                String savedDir = task.savedDir;
                String filename = task.filename;
                if (filename == null) {
                    filename = fileURL.substring(fileURL.lastIndexOf("/") + 1, fileURL.length());
                }
                String saveFilePath = savedDir + File.separator + filename;
                Intent intent = IntentUtils.validatedFileIntent(context, saveFilePath, task.mimeType);
                if (intent != null) {
                    context.startActivity(intent);
                    result.success(true);
                } else {
                    result.success(false);
                }
            } else {
                result.error("invalid_status", "only success task can be opened", null);
            }
        } else {
            result.error("invalid_task_id", "not found task corresponding to given task id", null);
        }
    }

    private void remove(MethodCall call, MethodChannel.Result result) {
        String taskId = call.argument("task_id");
        boolean shouldDeleteContent = call.argument("should_delete_content");
        final DownloadTask task = taskDao.loadTask(taskId);
        if (task != null) {
            if (task.status == DownloadStatus.ENQUEUED || task.status == DownloadStatus.RUNNING) {
                WorkManager.getInstance(context).cancelWorkById(UUID.fromString(taskId));
            }
            if (shouldDeleteContent) {
                String filename = task.filename;
                if (filename == null) {
                    filename = task.url.substring(task.url.lastIndexOf("/") + 1);
                }

                String saveFilePath = task.savedDir + File.separator + filename;
                File tempFile = new File(saveFilePath);
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
            taskDao.deleteTask(taskId);

            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    NotificationManagerCompat.from(context).cancel(task.primaryId);
                }
            }, 1000);

            result.success(null);
        } else {
            result.error("invalid_task_id", "not found task corresponding to given task id", null);
        }
    }
}
