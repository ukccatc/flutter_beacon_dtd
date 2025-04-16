package com.flutterbeacon;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;

// Removed the legacy import below:
// import io.flutter.plugin.common.PluginRegistry.Registrar;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;

// Note: The PluginRegistry.RequestPermissionsResultListener and
// PluginRegistry.ActivityResultListener remain in use. Their full names are
// referenced below so no import for Registrar is required.
public class FlutterBeaconPlugin implements FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler,
        io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener,
        io.flutter.plugin.common.PluginRegistry.ActivityResultListener {

  private static final BeaconParser iBeaconLayout = new BeaconParser()
          .setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24");

  static final int REQUEST_CODE_LOCATION = 1234;
  static final int REQUEST_CODE_BLUETOOTH = 5678;

  private FlutterPlugin.FlutterPluginBinding flutterPluginBinding;
  private ActivityPluginBinding activityPluginBinding;

  private FlutterBeaconScanner beaconScanner;
  private FlutterBeaconBroadcast beaconBroadcast;
  private FlutterPlatform platform;

  private BeaconManager beaconManager;
  Result flutterResult;
  private Result flutterResultBluetooth;
  private EventChannel.EventSink eventSinkLocationAuthorizationStatus;

  private MethodChannel channel;
  private EventChannel eventChannel;
  private EventChannel eventChannelMonitoring;
  private EventChannel eventChannelBluetoothState;
  private EventChannel eventChannelAuthorizationStatus;

  public FlutterBeaconPlugin() {
  }

  // Legacy v1 registration removed.
  // public static void registerWith(Registrar registrar) { ... }

  @Override
  public void onAttachedToEngine(@NonNull FlutterPlugin.FlutterPluginBinding binding) {
    this.flutterPluginBinding = binding;
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPlugin.FlutterPluginBinding binding) {
    this.flutterPluginBinding = null;
  }

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    this.activityPluginBinding = binding;
    setupChannels(flutterPluginBinding.getBinaryMessenger(), binding.getActivity());
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity();
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    onAttachedToActivity(binding);
  }

  @Override
  public void onDetachedFromActivity() {
    teardownChannels();
  }

  BeaconManager getBeaconManager() {
    return beaconManager;
  }

  private void setupChannels(BinaryMessenger messenger, Activity activity) {
    if (activityPluginBinding != null) {
      activityPluginBinding.addActivityResultListener(this);
      activityPluginBinding.addRequestPermissionsResultListener(this);
    }

    beaconManager = BeaconManager.getInstanceForApplication(activity.getApplicationContext());
    if (!beaconManager.getBeaconParsers().contains(iBeaconLayout)) {
      beaconManager.getBeaconParsers().clear();
      beaconManager.getBeaconParsers().add(iBeaconLayout);
    }

    platform = new FlutterPlatform(activity);
    beaconScanner = new FlutterBeaconScanner(this, activity);
    beaconBroadcast = new FlutterBeaconBroadcast(activity, iBeaconLayout);

    channel = new MethodChannel(messenger, "flutter_beacon");
    channel.setMethodCallHandler(this);

    eventChannel = new EventChannel(messenger, "flutter_beacon_event");
    eventChannel.setStreamHandler(beaconScanner.rangingStreamHandler);

    eventChannelMonitoring = new EventChannel(messenger, "flutter_beacon_event_monitoring");
    eventChannelMonitoring.setStreamHandler(beaconScanner.monitoringStreamHandler);

    eventChannelBluetoothState = new EventChannel(messenger, "flutter_bluetooth_state_changed");
    eventChannelBluetoothState.setStreamHandler(new FlutterBluetoothStateReceiver(activity));

    eventChannelAuthorizationStatus = new EventChannel(messenger, "flutter_authorization_status_changed");
    eventChannelAuthorizationStatus.setStreamHandler(locationAuthorizationStatusStreamHandler);
  }

  private void teardownChannels() {
    if (activityPluginBinding != null) {
      activityPluginBinding.removeActivityResultListener(this);
      activityPluginBinding.removeRequestPermissionsResultListener(this);
    }
    platform = null;
    beaconBroadcast = null;
    channel.setMethodCallHandler(null);
    eventChannel.setStreamHandler(null);
    eventChannelMonitoring.setStreamHandler(null);
    eventChannelBluetoothState.setStreamHandler(null);
    eventChannelAuthorizationStatus.setStreamHandler(null);

    channel = null;
    eventChannel = null;
    eventChannelMonitoring = null;
    eventChannelBluetoothState = null;
    eventChannelAuthorizationStatus = null;

    activityPluginBinding = null;
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull final Result result) {
    if (call.method.equals("initialize")) {
      if (beaconManager != null && !beaconManager.isBound(beaconScanner.beaconConsumer)) {
        this.flutterResult = result;
        this.beaconManager.bind(beaconScanner.beaconConsumer);
        return;
      }
      result.success(true);
      return;
    }

    if (call.method.equals("initializeAndCheck")) {
      initializeAndCheck(result);
      return;
    }

    if (call.method.equals("setScanPeriod")) {
      int scanPeriod = call.argument("scanPeriod");
      this.beaconManager.setForegroundScanPeriod(scanPeriod);
      try {
        this.beaconManager.updateScanPeriods();
        result.success(true);
      } catch (RemoteException e) {
        result.success(false);
      }
      return;
    }

    if (call.method.equals("setBetweenScanPeriod")) {
      int betweenScanPeriod = call.argument("betweenScanPeriod");
      this.beaconManager.setForegroundBetweenScanPeriod(betweenScanPeriod);
      try {
        this.beaconManager.updateScanPeriods();
        result.success(true);
      } catch (RemoteException e) {
        result.success(false);
      }
      return;
    }

    if (call.method.equals("setLocationAuthorizationTypeDefault")) {
      result.success(true);
      return;
    }

    if (call.method.equals("authorizationStatus")) {
      result.success(platform.checkLocationServicesPermission() ? "ALLOWED" : "NOT_DETERMINED");
      return;
    }

    if (call.method.equals("checkLocationServicesIfEnabled")) {
      result.success(platform.checkLocationServicesIfEnabled());
      return;
    }

    if (call.method.equals("bluetoothState")) {
      try {
        boolean flag = platform.checkBluetoothIfEnabled();
        result.success(flag ? "STATE_ON" : "STATE_OFF");
        return;
      } catch (RuntimeException ignored) {
      }
      result.success("STATE_UNSUPPORTED");
      return;
    }

    if (call.method.equals("requestAuthorization")) {
      if (!platform.checkLocationServicesPermission()) {
        this.flutterResult = result;
        platform.requestAuthorization();
        return;
      }
      if (eventSinkLocationAuthorizationStatus != null) {
        eventSinkLocationAuthorizationStatus.success("ALLOWED");
      }
      result.success(true);
      return;
    }

    if (call.method.equals("openBluetoothSettings")) {
      if (!platform.checkBluetoothIfEnabled()) {
        this.flutterResultBluetooth = result;
        platform.openBluetoothSettings();
        return;
      }
      result.success(true);
      return;
    }

    if (call.method.equals("openLocationSettings")) {
      platform.openLocationSettings();
      result.success(true);
      return;
    }

    if (call.method.equals("openApplicationSettings")) {
      result.notImplemented();
      return;
    }

    if (call.method.equals("close")) {
      if (beaconManager != null) {
        beaconScanner.stopRanging();
        beaconManager.removeAllRangeNotifiers();
        beaconScanner.stopMonitoring();
        beaconManager.removeAllMonitorNotifiers();
        if (beaconManager.isBound(beaconScanner.beaconConsumer)) {
          beaconManager.unbind(beaconScanner.beaconConsumer);
        }
      }
      result.success(true);
      return;
    }

    if (call.method.equals("startBroadcast")) {
      beaconBroadcast.startBroadcast(call.arguments, result);
      return;
    }

    if (call.method.equals("stopBroadcast")) {
      beaconBroadcast.stopBroadcast(result);
      return;
    }

    if (call.method.equals("isBroadcasting")) {
      beaconBroadcast.isBroadcasting(result);
      return;
    }

    if (call.method.equals("isBroadcastSupported")) {
      result.success(platform.isBroadcastSupported());
      return;
    }

    result.notImplemented();
  }

  private void initializeAndCheck(Result result) {
    if (platform.checkLocationServicesPermission()
            && platform.checkBluetoothIfEnabled()
            && platform.checkLocationServicesIfEnabled()) {
      if (result != null) {
        result.success(true);
        return;
      }
    }
    flutterResult = result;
    if (!platform.checkBluetoothIfEnabled()) {
      platform.openBluetoothSettings();
      return;
    }
    if (!platform.checkLocationServicesPermission()) {
      platform.requestAuthorization();
      return;
    }
    if (!platform.checkLocationServicesIfEnabled()) {
      platform.openLocationSettings();
      return;
    }
    if (beaconManager != null && !beaconManager.isBound(beaconScanner.beaconConsumer)) {
      if (result != null) {
        this.flutterResult = result;
      }
      beaconManager.bind(beaconScanner.beaconConsumer);
      return;
    }
    if (result != null) {
      result.success(true);
    }
  }

  private final EventChannel.StreamHandler locationAuthorizationStatusStreamHandler = new EventChannel.StreamHandler() {
    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
      eventSinkLocationAuthorizationStatus = events;
    }
    @Override
    public void onCancel(Object arguments) {
      eventSinkLocationAuthorizationStatus = null;
    }
  };

  // region ACTIVITY CALLBACK
  @Override
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    if (requestCode != REQUEST_CODE_LOCATION) {
      return false;
    }
    boolean locationServiceAllowed = false;
    if (permissions.length > 0 && grantResults.length > 0) {
      String permission = permissions[0];
      if (!platform.shouldShowRequestPermissionRationale(permission)) {
        int grantResult = grantResults[0];
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
          locationServiceAllowed = true;
        }
        if (eventSinkLocationAuthorizationStatus != null) {
          eventSinkLocationAuthorizationStatus.success(locationServiceAllowed ? "ALLOWED" : "DENIED");
        }
      } else {
        if (eventSinkLocationAuthorizationStatus != null) {
          eventSinkLocationAuthorizationStatus.success("NOT_DETERMINED");
        }
      }
    } else {
      if (eventSinkLocationAuthorizationStatus != null) {
        eventSinkLocationAuthorizationStatus.success("NOT_DETERMINED");
      }
    }
    if (flutterResult != null) {
      if (locationServiceAllowed) {
        flutterResult.success(true);
      } else {
        flutterResult.error("Beacon", "location services not allowed", null);
      }
      this.flutterResult = null;
    }
    return locationServiceAllowed;
  }

  @Override
  public boolean onActivityResult(int requestCode, int resultCode, Intent intent) {
    boolean bluetoothEnabled = requestCode == REQUEST_CODE_BLUETOOTH && resultCode == Activity.RESULT_OK;
    if (bluetoothEnabled) {
      if (!platform.checkLocationServicesPermission()) {
        platform.requestAuthorization();
      } else {
        if (flutterResultBluetooth != null) {
          flutterResultBluetooth.success(true);
          flutterResultBluetooth = null;
        } else if (flutterResult != null) {
          flutterResult.success(true);
          flutterResult = null;
        }
      }
    } else {
      if (flutterResultBluetooth != null) {
        flutterResultBluetooth.error("Beacon", "bluetooth disabled", null);
        flutterResultBluetooth = null;
      } else if (flutterResult != null) {
        flutterResult.error("Beacon", "bluetooth disabled", null);
        flutterResult = null;
      }
    }
    return bluetoothEnabled;
  }
  // endregion
}