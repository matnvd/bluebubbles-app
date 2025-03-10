package com.bluebubbles.messaging.services;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.messaging.RemoteMessage;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterShellArgs;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.dart.DartExecutor.DartCallback;
import io.flutter.embedding.engine.plugins.shim.ShimPluginRegistry;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.FlutterCallbackInformation;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.bluebubbles.messaging.method_call_handler.handlers.InitializeBackgroundHandle.BACKGROUND_SERVICE_SHARED_PREF;
import static com.bluebubbles.messaging.method_call_handler.handlers.InitializeBackgroundHandle.BACKGROUND_HANDLE_SHARED_PREF_KEY;

import com.bluebubbles.messaging.MainActivity;
import com.bluebubbles.messaging.method_call_handler.handlers.ClearChatNotifs;
import com.bluebubbles.messaging.method_call_handler.handlers.CreateNotificationChannel;
import com.bluebubbles.messaging.method_call_handler.handlers.DownloadHandler;
import com.bluebubbles.messaging.method_call_handler.handlers.FirebaseAuth;
import com.bluebubbles.messaging.method_call_handler.handlers.IncomingFaceTimeNotification;
import com.bluebubbles.messaging.method_call_handler.handlers.GetServerUrl;
import com.bluebubbles.messaging.method_call_handler.handlers.InitializeBackgroundHandle;
import com.bluebubbles.messaging.method_call_handler.handlers.NewMessageNotification;
import com.bluebubbles.messaging.method_call_handler.handlers.OpenLink;
import com.bluebubbles.messaging.method_call_handler.handlers.PushShareTargets;
import com.bluebubbles.messaging.method_call_handler.handlers.SetNextRestart;
import com.bluebubbles.messaging.method_call_handler.handlers.OpenContactForm;
import com.bluebubbles.messaging.method_call_handler.handlers.ViewContactForm;
import com.bluebubbles.messaging.workers.DartWorker;

/**
 * An background execution abstraction which handles initializing a background isolate running a
 * callback dispatcher, used to invoke Dart callbacks while backgrounded.
 */
public class FlutterFirebaseMessagingBackgroundExecutor implements MethodCallHandler {
    private static final String TAG = "FLTFireBGExecutor";

    private final AtomicBoolean isCallbackDispatcherReady = new AtomicBoolean(false);
    /**
     * The {@link MethodChannel} that connects the Android side of this plugin with the background
     * Dart isolate that was created by this plugin.
     */
    private MethodChannel backgroundChannel;

    private FlutterEngine backgroundFlutterEngine;

    /**
     * Sets the Dart callback handle for the Dart method that is responsible for initializing the
     * background Dart isolate, preparing it to receive Dart callback tasks requests.
     */
    public static void setCallbackDispatcher(long callbackHandle) {
        Context context = ContextHolder.getApplicationContext();
        SharedPreferences prefs =
                context.getSharedPreferences(BACKGROUND_SERVICE_SHARED_PREF, Context.MODE_PRIVATE);
        prefs.edit().putLong(BACKGROUND_HANDLE_SHARED_PREF_KEY, callbackHandle).apply();
    }

    /**
     * Returns true when the background isolate has started and is ready to handle background
     * messages.
     */
    public boolean isNotRunning() {
        return !isCallbackDispatcherReady.get();
    }

    private void onInitialized() {
        isCallbackDispatcherReady.set(true);
        FlutterFirebaseMessagingBackgroundService.onInitialized();
    }

    @Override
    public void onMethodCall(MethodCall call, @NonNull Result result) {
        String method = call.method;
        Context context = ContextHolder.getApplicationContext();
        if (call.method.equals(FirebaseAuth.TAG)) {
            new FirebaseAuth(context, call, result).Handle();
        } else if (call.method.equals(CreateNotificationChannel.TAG)) {
            new CreateNotificationChannel(context, call, result).Handle();
        } else if (call.method.equals(NewMessageNotification.TAG)) {
            new NewMessageNotification(context, call, result).Handle();
        } else if (call.method.equals(IncomingFaceTimeNotification.TAG)) {
            new IncomingFaceTimeNotification(context, call, result).Handle();
        } else if (call.method.equals(OpenLink.TAG)) {
            new OpenLink(context, call, result).Handle();
        } else if (call.method.equals(ClearChatNotifs.TAG)) {
            new ClearChatNotifs(context, call, result).Handle();
        } else if(call.method.equals(PushShareTargets.TAG)) {
            new PushShareTargets(context, call, result).Handle();
        } else if (call.method.equals(InitializeBackgroundHandle.TAG)) {
            new InitializeBackgroundHandle(context, call, result).Handle();
        } else if (call.method.equals(GetServerUrl.TAG)) {
            new GetServerUrl(context, call, result).Handle();
        } else if (call.method.equals(SetNextRestart.TAG)) {
            new SetNextRestart(context, call, result).Handle();
        } else if (call.method.equals(DownloadHandler.TAG)) {
            new DownloadHandler(context, call, result).Handle();
        } else if (call.method.equals(OpenContactForm.TAG)) {
            new OpenContactForm(context, call, result).Handle();
        } else if (call.method.equals(ViewContactForm.TAG)) {
            new ViewContactForm(context, call, result).Handle();
        } else if (method.equals("MessagingBackground#initialized")) {
            // This message is sent by the background method channel as soon as the background isolate
            // is running. From this point forward, the Android side of this plugin can send
            // callback handles through the background method channel, and the Dart side will execute
            // the Dart methods corresponding to those callback handles.
            onInitialized();
            result.success(true);
        } else {
            result.notImplemented();
        }
    }

    /**
     * Starts running a background Dart isolate within a new {@link FlutterEngine} using a previously
     * used entrypoint.
     *
     * <p>The isolate is configured as follows:
     *
     * <ul>
     *   <li>Bundle Path: {@code io.flutter.view.FlutterMain.findAppBundlePath(context)}.
     *   <li>Entrypoint: The Dart method used the last time this plugin was initialized in the
     *       foreground.
     *   <li>Run args: none.
     * </ul>
     *
     * <p>Preconditions:
     *
     * <ul>
     *   <li>The given callback must correspond to a registered Dart callback. If the handle does not
     *       resolve to a Dart callback then this method does nothing.
     *   <li>A static {@link #pluginRegistrantCallback} must exist, otherwise a {@link
     *       PluginRegistrantException} will be thrown.
     * </ul>
     */
    public void startBackgroundIsolate() {
        if (isNotRunning()) {
            long callbackHandle = getPluginCallbackHandle();
            if (callbackHandle != 0) {
                Log.i(TAG, "Found background isolate callback handle");
                startBackgroundIsolate(callbackHandle, null);
            } else {
                Log.i(TAG, "No callback handler available for background isolate");
            }
        }
    }

    /**
     * Starts running a background Dart isolate within a new {@link FlutterEngine}.
     *
     * <p>The isolate is configured as follows:
     *
     * <ul>
     *   <li>Bundle Path: {@code io.flutter.view.FlutterMain.findAppBundlePath(context)}.
     *   <li>Entrypoint: The Dart method represented by {@code callbackHandle}.
     *   <li>Run args: none.
     * </ul>
     *
     * <p>Preconditions:
     *
     * <ul>
     *   <li>The given {@code callbackHandle} must correspond to a registered Dart callback. If the
     *       handle does not resolve to a Dart callback then this method does nothing.
     *   <li>A static {@link #pluginRegistrantCallback} must exist, otherwise a {@link
     *       PluginRegistrantException} will be thrown.
     * </ul>
     */
    public void startBackgroundIsolate(long callbackHandle, FlutterShellArgs shellArgs) {
        if (backgroundFlutterEngine != null) {
            Log.e(TAG, "Background isolate already started.");
            return;
        }

        Handler mainHandler = new Handler(Looper.getMainLooper());
        Runnable myRunnable =
                () -> {
                    io.flutter.view.FlutterMain.startInitialization(ContextHolder.getApplicationContext());
                    io.flutter.view.FlutterMain.ensureInitializationCompleteAsync(
                            ContextHolder.getApplicationContext(),
                            null,
                            mainHandler,
                            () -> {
                                String appBundlePath = io.flutter.view.FlutterMain.findAppBundlePath();
                                AssetManager assets = ContextHolder.getApplicationContext().getAssets();
                                if (isNotRunning()) {
                                    if (shellArgs != null) {
                                        Log.i(
                                                TAG,
                                                "Creating background FlutterEngine instance, with args: "
                                                        + Arrays.toString(shellArgs.toArray()));
                                        backgroundFlutterEngine =
                                                new FlutterEngine(
                                                        ContextHolder.getApplicationContext(), shellArgs.toArray());
                                    } else {
                                        Log.i(TAG, "Creating background FlutterEngine instance.");
                                        backgroundFlutterEngine =
                                                new FlutterEngine(ContextHolder.getApplicationContext());
                                    }
                                    // We need to create an instance of `FlutterEngine` before looking up the
                                    // callback. If we don't, the callback cache won't be initialized and the
                                    // lookup will fail.
                                    FlutterCallbackInformation flutterCallback =
                                            FlutterCallbackInformation.lookupCallbackInformation(callbackHandle);
                                    DartExecutor executor = backgroundFlutterEngine.getDartExecutor();
                                    initializeMethodChannel(executor);
                                    DartCallback dartCallback =
                                            new DartCallback(assets, appBundlePath, flutterCallback);

                                    executor.executeDartCallback(dartCallback);
                                }
                            });
                };
        mainHandler.post(myRunnable);
    }

    boolean isDartBackgroundHandlerRegistered() {
        return getPluginCallbackHandle() != 0;
    }

    /**
     * Executes the desired Dart callback in a background Dart isolate.
     *
     * <p>The given {@code intent} should contain a {@code long} extra called "callbackHandle", which
     * corresponds to a callback registered with the Dart VM.
     */
    public void executeDartCallbackInBackgroundIsolate(Intent intent, final CountDownLatch latch) {
        if (backgroundFlutterEngine == null) {
            Log.i(
                    TAG,
                    "A background message could not be handled in Dart as no onBackgroundMessage handler has been registered.");
            return;
        }

        Result result = null;
        if (latch != null) {
            result =
                    new Result() {
                        @Override
                        public void success(Object result) {
                            // If another thread is waiting, then wake that thread when the callback returns a result.
                            latch.countDown();
                        }

                        @Override
                        public void error(String errorCode, String errorMessage, Object errorDetails) {
                            latch.countDown();
                        }

                        @Override
                        public void notImplemented() {
                            latch.countDown();
                        }
                    };
        }

        // Handle the message event in Dart.
        RemoteMessage remoteMessage =
                intent.getParcelableExtra("notification");
        if (remoteMessage != null) {
            backgroundChannel.invokeMethod(remoteMessage.getData().get("type"), remoteMessage.getData().get("data"),
                    result);
        } else {
            Log.e(TAG, "RemoteMessage instance not found in Intent.");
        }
    }

    /** Get the registered Dart callback handle for the messaging plugin. Returns 0 if not set. */
    private long getPluginCallbackHandle() {
        if (ContextHolder.getApplicationContext() == null) {
            return 0;
        }
        SharedPreferences prefs =
                ContextHolder.getApplicationContext().getSharedPreferences(BACKGROUND_SERVICE_SHARED_PREF, Context.MODE_PRIVATE);
        return prefs.getLong(BACKGROUND_HANDLE_SHARED_PREF_KEY, 0);
    }

    private void initializeMethodChannel(BinaryMessenger isolate) {
        // backgroundChannel is the channel responsible for receiving the following messages from
        // the background isolate that was setup by this plugin method call:
        // - "FirebaseBackgroundMessaging#initialized"
        //
        // This channel is also responsible for sending requests from Android to Dart to execute Dart
        // callbacks in the background isolate.
        backgroundChannel =
                new MethodChannel(isolate, "com.bluebubbles.messaging");
        backgroundChannel.setMethodCallHandler(this);
    }
}