package chan.jas0n.ANT2BLE_PowerMeter;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.EnumSet;

import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

//antplus API
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikePowerPcc;
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikePowerPcc.DataSource;
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikePowerPcc.ICalculatedPowerReceiver;
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState;
import com.dsi.ant.plugins.antplus.pcc.defines.EventFlag;
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult;
import com.dsi.ant.plugins.antplus.pcc.defines.RequestStatus;
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc.IDeviceStateChangeReceiver;
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc.IPluginAccessResultReceiver;
import com.dsi.ant.plugins.antplus.pccbase.AntPlusCommonPcc.IRequestFinishedReceiver;
import com.dsi.ant.plugins.antplus.pccbase.MultiDeviceSearch.MultiDeviceSearchResult;
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle;

//---BLE API----//
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.ParcelUuid;

public class MainActivity extends AppCompatActivity {
    //----------------Define for ANT+-------------------//
    AntPlusBikePowerPcc pwrPcc = null;
    PccReleaseHandle<AntPlusBikePowerPcc> releaseHandle = null;

    //NOTE: We're using 2.07m as the wheel circumference to pass to the calculated events
    BigDecimal wheelCircumferenceInMeters = new BigDecimal("2.07");

    //----------------Define for BLE-------------------//
    private BluetoothGattCharacteristic mCyclingPowerMeasurementCharacteristic;
    private BluetoothGattCharacteristic mCyclingPowerFeatureCharacteristic;
    private BluetoothGattCharacteristic mSensorLocation;
    private BluetoothGattService mCyclingPowerService;
    TextView BLE_Status;
    private ServiceFragment mCurrentServiceFragment;
    private HashSet<BluetoothDevice> mBluetoothDevices;
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private AdvertiseData mAdvData;
    private AdvertiseData mAdvScanResponse;
    private AdvertiseSettings mAdvSettings;
    private BluetoothLeAdvertiser mAdvertiser;

    int BLE_calculatedPower = 0;
    int CYCLING_POWER_MEASUREMENT_VALUE_FORMAT = BluetoothGattCharacteristic.FORMAT_SINT16;
    UUID CYCLING_POWER_SERVICE_UUID = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb");
    UUID CYCLING_POWER_MEASUREMENT_UUID = UUID.fromString("00002A63-0000-1000-8000-00805f9b34fb");
    UUID CYCLING_POWER_FEATURE_UUID = UUID.fromString("00002A65-0000-1000-8000-00805f9b34fb");
    UUID SENSOR_LOCATION_UUID = UUID.fromString("00002A5D-0000-1000-8000-00805f9b34fb");

    //--------Define TextView--------//
    TextView textView_status; //needed, basic info
    TextView textView_BLEPower;
    TextView textView_BLE_Status;

    //-----------Define Button--------------//
    Button button_ant_reset;
    Button button_reset_BLE;

    //Enables communication between a client and the BikePower plugin service.//
    public IPluginAccessResultReceiver<AntPlusBikePowerPcc> mResultReceiver = new IPluginAccessResultReceiver<AntPlusBikePowerPcc>()
    {
        @Override
        public void onResultReceived(AntPlusBikePowerPcc result,
                                     RequestAccessResult resultCode, DeviceState initialDeviceState)
        {
            switch (resultCode)
            {
                case SUCCESS:
                    pwrPcc = result;
                    textView_status.setText(result.getDeviceName() + ": " + initialDeviceState);
                    subscribeToEvents();
                    break;
                case CHANNEL_NOT_AVAILABLE:
                    Toast.makeText(MainActivity.this,
                            "Channel Not Available",
                            Toast.LENGTH_SHORT).show();
                    textView_status.setText("Error. Please reset ANT+ connection.");
                    break;
                case ADAPTER_NOT_DETECTED:
                    Toast.makeText(MainActivity.this,
                                    "ANT Adapter Not Available. Built-in ANT hardware or external adapter required.",
                                    Toast.LENGTH_SHORT).show();
                    textView_status.setText("Error. Please reset ANT+ connection.");
                    break;
                case BAD_PARAMS:
                    // Note: Since we compose all the params ourself, we should
                    // never see this result
                    Toast.makeText(MainActivity.this, "Bad request parameters.",
                            Toast.LENGTH_SHORT).show();
                    textView_status.setText("Error. Please reset ANT+ connection.");
                    break;
                case OTHER_FAILURE:
                    Toast.makeText(MainActivity.this,
                            "RequestAccess failed. See logcat for details.", Toast.LENGTH_SHORT)
                            .show();
                    textView_status.setText("Error. Please reset ANT+ connection.");
                    break;
                case DEPENDENCY_NOT_INSTALLED:
                    textView_status.setText("Error. Please reset ANT+ connection.");
                    AlertDialog.Builder adlgBldr = new AlertDialog.Builder(
                            MainActivity.this);
                    adlgBldr.setTitle("Missing Dependency");
                    adlgBldr.setMessage("The required service\n\""
                            + AntPlusBikePowerPcc.getMissingDependencyName()
                            + "\"\n was not found. You need to install the ANT+ Plugins service or"
                            + "you may need to update your existing version if you already have it"
                            + ". Do you want to launch the Play Store to get it?");
                    adlgBldr.setCancelable(true);
                    adlgBldr.setPositiveButton("Go to Store", new OnClickListener()
                    {
                        @Override
                        public void onClick(DialogInterface dialog, int which)
                        {
                            Intent startStore = null;
                            startStore = new Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id="
                                            + AntPlusBikePowerPcc.getMissingDependencyPackageName()));
                            startStore.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                            MainActivity.this.startActivity(startStore);
                        }
                    });
                    adlgBldr.setNegativeButton("Cancel", new OnClickListener()
                    {
                        @Override
                        public void onClick(DialogInterface dialog, int which)
                        {
                            dialog.dismiss();
                        }
                    });

                    final AlertDialog waitDialog = adlgBldr.create();
                    waitDialog.show();
                    break;
                case USER_CANCELLED:
                    textView_status.setText("Cancelled. Please reset ANT+ connection.");
                    break;
                case UNRECOGNIZED:
                    Toast.makeText(MainActivity.this,
                            "PluginLib Upgrade Required?" + resultCode, Toast.LENGTH_SHORT).show();
                    textView_status.setText("Error. Please reset ANT+ connection.");
                    break;
                default:
                    Toast.makeText(MainActivity.this,
                            "Unrecognized result: " + resultCode, Toast.LENGTH_SHORT).show();
                    textView_status.setText("Error. Please reset ANT+ connection.");
                    break;
            }
        }

        //@Override
        private void subscribeToEvents()
        {
            advertise();

            pwrPcc.subscribeCalculatedPowerEvent(new ICalculatedPowerReceiver()
            {
                @Override
                public void onNewCalculatedPower(
                        final long estTimestamp, final EnumSet<EventFlag> eventFlags,
                        final DataSource dataSource,
                        final BigDecimal calculatedPower)
                {
                    runOnUiThread(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            BLE_calculatedPower = calculatedPower.intValue();
                            putvalue();
                        }
                    });
                }
            });
            textView_BLE_Status.setText("Power Meter Connected, please search for 'ANT+2BLE PowerMeter'.\n If it is idled for too long, please reset BLE connection.");

        }
    };

//-------------------------------------------------------------------------//
IDeviceStateChangeReceiver mDeviceStateChangeReceiver = new IDeviceStateChangeReceiver()
{
    @Override
    public void onDeviceStateChange(final DeviceState newDeviceState)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                textView_status.setText(pwrPcc.getDeviceName() + ": " + newDeviceState);
                if(newDeviceState.equals("DEAD")){
                    resetPcc();
                }
            }
        });
    }
};
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mBluetoothDevices = new HashSet<>();

        //-----------------Set reading to textview items--------------------------------------------//
        textView_status = (TextView)findViewById(R.id.textView_Status);
        textView_BLEPower = (TextView)findViewById(R.id.textView_BLEPower);
        textView_BLE_Status = (TextView)findViewById(R.id.textView_BLE_status);

        button_ant_reset = (Button)findViewById(R.id.button_ant_reset);
        button_reset_BLE = (Button)findViewById(R.id.button_reset_BLE);

        //------------------Finish Receiver-------------//
        final IRequestFinishedReceiver requestFinishedReceiver =
                new IRequestFinishedReceiver()
                {
                    @Override
                    public void onNewRequestFinished(final RequestStatus requestStatus)
                    {
                        runOnUiThread(
                                new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        switch(requestStatus)
                                        {
                                            case SUCCESS:
                                                Toast.makeText(MainActivity.this, "Request Successfully Sent", Toast.LENGTH_SHORT).show();
                                                break;
                                            case FAIL_PLUGINS_SERVICE_VERSION:
                                                Toast.makeText(MainActivity.this,
                                                        "Plugin Service Upgrade Required?",
                                                        Toast.LENGTH_SHORT).show();
                                                break;
                                            default:
                                                Toast.makeText(MainActivity.this, "Request Failed to be Sent", Toast.LENGTH_SHORT).show();
                                                break;
                                        }
                                    }
                                });
                    }
                };
        //-----------------button action--------------//
        button_ant_reset.setOnClickListener(
                new View.OnClickListener()
                {
                    @Override
                    public void onClick(View v)
                    {
                        resetPcc();
                    }
                });

        //----------------notifyButtonListener-----------//
        button_reset_BLE.setOnClickListener(
                new View.OnClickListener()
                {
                    @Override
                    public void onClick(View v)
                    {
                        disconnectFromDevices();
                        advertise();
                    }
                });
        resetPcc();
    }

    //-------------reset all connection------------//
    private void resetPcc()
    {
        if(releaseHandle != null)
        {
            releaseHandle.close();
        }
        button_ant_reset.setEnabled(true);
        button_reset_BLE.setEnabled(false);
        textView_status.setText("Connecting...");
        textView_BLEPower.setText("---");
        textView_BLE_Status.setText("BLE service will start automatically when Power Meter is connected.");

        Intent intent = getIntent();
        if (intent.hasExtra(Activity_MultiDeviceSearchSampler.EXTRA_KEY_MULTIDEVICE_SEARCH_RESULT))
        {
            // device has already been selected through the multi-device search
            MultiDeviceSearchResult result = intent
                    .getParcelableExtra(Activity_MultiDeviceSearchSampler.EXTRA_KEY_MULTIDEVICE_SEARCH_RESULT);
            releaseHandle = AntPlusBikePowerPcc.requestAccess(this, result.getAntDeviceNumber(), 0,
                    mResultReceiver, mDeviceStateChangeReceiver);
        } else
        {
            // starts the plugins UI search
            releaseHandle = AntPlusBikePowerPcc.requestAccess(this, this, mResultReceiver,
                    mDeviceStateChangeReceiver);
        }
    }

    //--------------------------------------//
    @Override
    protected void onDestroy()
    {
        releaseHandle.close();
        super.onDestroy();
    }

    //-------------------ant+ part end---------//
    //-------------------BLE Part Start---------//
    private void advertise() {
        mCyclingPowerMeasurementCharacteristic = new BluetoothGattCharacteristic(CYCLING_POWER_MEASUREMENT_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
        mCyclingPowerFeatureCharacteristic = new BluetoothGattCharacteristic(CYCLING_POWER_FEATURE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ, 0);
        mSensorLocation = new BluetoothGattCharacteristic(SENSOR_LOCATION_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ, 0);

        mCyclingPowerService = new BluetoothGattService(CYCLING_POWER_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        mCyclingPowerService.addCharacteristic(mCyclingPowerMeasurementCharacteristic);
        mCyclingPowerService.addCharacteristic(mCyclingPowerFeatureCharacteristic);
        mCyclingPowerService.addCharacteristic(mSensorLocation);

        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        mBluetoothAdapter.setName("ANT+2BLE PowerMeter");
        mGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
        // If the user disabled Bluetooth when the app was in the background,
        // openGattServer() will return null.
        if (mGattServer == null) {
            ensureBleFeaturesAvailable();
            return;
        }
        // Add a service for a total of three services (Generic Attribute and Generic Access
        // are present by default).
        mGattServer.addService(mCyclingPowerService);
        //---- define the settings that are used when advertising ---//
        mAdvSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        //---- define a UUID that is used for identifying your packets ---//
        ParcelUuid pUuid = new ParcelUuid(UUID.fromString(getString(R.string.ble_uuid)));
        //---- create the ParcelUuid, AdvertiseData object, and broadcast some additional data as an additional service ---//
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(pUuid)
                .addServiceData(pUuid, "Data".getBytes(Charset.forName("UTF-8")))
                .build();
        //---- to advertise over Bluetooth LE with your device is create a callback that listens for success or failure when advertising ---//
        mAdvCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
            }
            @Override
            public void onStartFailure(int errorCode) {
                Log.e("BLE", "Advertising onStartFailure: " + errorCode);
                super.onStartFailure(errorCode);
            }
        };
        mAdvData = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(true)
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(mCyclingPowerService.getUuid()))
                .build();
        mAdvScanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
        //---- retrieve the BluetoothLeAdvertiser from the Android BluetoothAdapter ---//
        mAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        //--- start advertising over the BluetoothLeAdvertiser ---//
        mAdvertiser.startAdvertising(mAdvSettings, mAdvData, mAdvScanResponse, mAdvCallback);
        button_reset_BLE.setEnabled(true);
        textView_BLE_Status.setText("Power Meter Connected, please search for 'ANT+2BLE PowerMeter'.\n If it is idled for too long, please reset BLE connection.");

    }
    private void putvalue(){
        mCyclingPowerMeasurementCharacteristic.setValue(new byte[]{0b000000000, 0, 0, 0, 0, 0, 0});
        // Characteristic Value: [flags, 0, 0, 0]
        mCyclingPowerMeasurementCharacteristic.setValue(BLE_calculatedPower,
                CYCLING_POWER_MEASUREMENT_VALUE_FORMAT,
        /* offset */ 2);
        // Characteristic Value: [flags, 0, 0, PowerMeasureValue, 0, 0, 0]
        textView_BLEPower.setText(Integer.toString(BLE_calculatedPower));
        mCyclingPowerFeatureCharacteristic.setValue(new byte[]{0b000000000});
        mSensorLocation.setValue(new byte[]{(byte) 15});

        sendNotificationToDevices(mCyclingPowerMeasurementCharacteristic);
    }
    private static final String TAG = MainActivity.class.getCanonicalName();

    private static final UUID CHARACTERISTIC_USER_DESCRIPTION_UUID = UUID
            .fromString("00002901-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUID
            .fromString("00002902-0000-1000-8000-00805f9b34fb");

    private AdvertiseCallback mAdvCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            Log.e(TAG, "Not broadcasting: " + errorCode);
            int statusText;
            switch (errorCode) {
                case ADVERTISE_FAILED_ALREADY_STARTED:
                    statusText = R.string.status_advertising;
                    Log.w(TAG, "App was already advertising");
                    break;
                case ADVERTISE_FAILED_DATA_TOO_LARGE:
                    statusText = R.string.status_advDataTooLarge;
                    break;
                case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    statusText = R.string.status_advFeatureUnsupported;
                    break;
                case ADVERTISE_FAILED_INTERNAL_ERROR:
                    statusText = R.string.status_advInternalError;
                    break;
                case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    statusText = R.string.status_advTooManyAdvertisers;
                    break;
                default:
                    statusText = R.string.status_notAdvertising;
                    Log.wtf(TAG, "Unhandled error: " + errorCode);
            }
            textView_BLE_Status.setText(statusText);
        }

        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            Log.v(TAG, "Broadcasting");
            textView_BLE_Status.setText("Broadcasting");
        }
    };

    private BluetoothGattServer mGattServer;
    private final BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, final int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    mBluetoothDevices.add(device);
                    updateConnectedDevicesStatus();
                    Log.v(TAG, "Connected to device: " + device.getAddress());
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    mBluetoothDevices.remove(device);
                    updateConnectedDevicesStatus();
                    Log.v(TAG, "Disconnected from device");
                }
            } else {
                mBluetoothDevices.remove(device);
                updateConnectedDevicesStatus();
                // There are too many gatt errors (some of them not even in the documentation) so we just
                // show the error to the user.
                final String errorMessage = getString(R.string.status_errorWhenConnecting) + ": " + status;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
                Log.e(TAG, "Error when connecting: " + status);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            Log.d(TAG, "Device tried to read characteristic: " + characteristic.getUuid());
            Log.d(TAG, "Value: " + Arrays.toString(characteristic.getValue()));
            if (offset != 0) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
            /* value (optional) */ null);
                return;
            }
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                    offset, characteristic.getValue());
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            super.onNotificationSent(device, status);
            Log.v(TAG, "Notification sent. Status: " + status);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite,
                    responseNeeded, offset, value);
            Log.v(TAG, "Characteristic Write request: " + Arrays.toString(value));
            int status = mCurrentServiceFragment.writeCharacteristic(characteristic, offset, value);
            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId, status,
            /* No need to respond with an offset */ 0,
            /* No need to respond with a value */ null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                            int offset, BluetoothGattDescriptor descriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor);
            Log.d(TAG, "Device tried to read descriptor: " + descriptor.getUuid());
            Log.d(TAG, "Value: " + Arrays.toString(descriptor.getValue()));
            if (offset != 0) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
            /* value (optional) */ null);
                return;
            }
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    descriptor.getValue());
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                                             int offset,
                                             byte[] value) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded,
                    offset, value);
            Log.v(TAG, "Descriptor Write Request " + descriptor.getUuid() + " " + Arrays.toString(value));
            int status = BluetoothGatt.GATT_SUCCESS;
            if (descriptor.getUuid() == CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
                BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
                boolean supportsNotifications = (characteristic.getProperties() &
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                boolean supportsIndications = (characteristic.getProperties() &
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

                if (!(supportsNotifications || supportsIndications)) {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                } else if (value.length != 2) {
                    status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    mCurrentServiceFragment.notificationsDisabled(characteristic);
                    descriptor.setValue(value);
                } else if (supportsNotifications &&
                        Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    mCurrentServiceFragment.notificationsEnabled(characteristic, false /* indicate */);
                    descriptor.setValue(value);
                } else if (supportsIndications &&
                        Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    mCurrentServiceFragment.notificationsEnabled(characteristic, true /* indicate */);
                    descriptor.setValue(value);
                } else {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                }
            } else {
                status = BluetoothGatt.GATT_SUCCESS;
                descriptor.setValue(value);
            }
            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId, status,
            /* No need to respond with offset */ 0,
            /* No need to respond with a value */ null);
            }
        }
    };
    private void updateConnectedDevicesStatus() {
        final String message = getString(R.string.status_devicesConnected) + " "
                + mBluetoothManager.getConnectedDevices(BluetoothGattServer.GATT).size();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }
    //@Override
    public void sendNotificationToDevices(BluetoothGattCharacteristic characteristic) {
        boolean indicate = (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_INDICATE)
                == BluetoothGattCharacteristic.PROPERTY_INDICATE;
        for (BluetoothDevice device : mBluetoothDevices) {
            // true for indication (acknowledge) and false for notification (unacknowledge).
            mGattServer.notifyCharacteristicChanged(device, characteristic, indicate);
            textView_BLE_Status.setText("Sending Data to BLE Device.");
        }
    }
    private void ensureBleFeaturesAvailable() {
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.bluetoothNotSupported, Toast.LENGTH_LONG).show();
            Log.e(TAG, "Bluetooth not supported");
            finish();
        } else if (!mBluetoothAdapter.isEnabled()) {
            // Make sure bluetooth is enabled.
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1);
            //advertise();
        }
    }
    private void disconnectFromDevices() {
        Log.d(TAG, "Disconnecting devices...");
        for (BluetoothDevice device : mBluetoothManager.getConnectedDevices(
                BluetoothGattServer.GATT)) {
            Log.d(TAG, "Devices: " + device.getAddress() + " " + device.getName());
            mGattServer.cancelConnection(device);
        }
    }
    //-------------BLE part end---------//
}