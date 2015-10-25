package com.sevenfloor.mtcsound;

import android.content.Context;
import android.content.Intent;
import android.media.AudioTrack;

import com.sevenfloor.mtcsound.handlers.*;
import com.sevenfloor.mtcsound.state.DeviceState;

import java.util.HashMap;
import java.util.Map;

public class Device {

    public static final int MEDIA_PLAYBACK_COMPLETE = 2;
    public static final int MEDIA_STARTED = 6;
    public static final int MEDIA_PAUSED = 7;
    public static final int MEDIA_STOPPED = 8;
    public static final int MEDIA_ERROR = 100;

    private Context context;
    private final Object lock = new Object();
    private final Map<String, ParameterHandler> handlers = new HashMap<>();
    private final HwInterface hardware = new HwInterface();
    private final Persister persister = new Persister();
    private boolean stateLoaded = false;
    private boolean i2cMode;

    public final DeviceState state = new DeviceState();

    public Device(Context context) {
        this.context = context;

        handlers.put("av_control_mode", new ControlModeHandler(this));


        i2cMode = checkHardware();
        //if (!i2cMode) return;
        // listen to power, to reapply state
        // on some devices, maybe 3066 it is known to lose system input after return from sleep
        handlers.put("rpt_power", new PowerHandler(this));

        // inputs
        handlers.put("av_channel_enter", new ChannelEnterHandler(this));
        handlers.put("av_channel_exit", new ChannelExitHandler(this));
        handlers.put("av_channel", new ChannelQueryHandler(this));
        handlers.put("av_phone", new PhoneHandler(this));

        // globals
        handlers.put("av_mute", new MuteHandler(this));
        handlers.put("av_volume", new VolumeHandler(this));
        handlers.put("av_phone_volume", new PhoneVolumeHandler(this));
        handlers.put("av_balance", new BalanceHandler(this));

        // profile-specific
        handlers.put("av_gain", new InputGainHandler(this));

        handlers.put("av_eq_on", new EqualizerOnHandler(this));
        handlers.put("av_eq_bass", new BassHandler(this));
        handlers.put("av_eq_middle", new MiddleHandler(this));
        handlers.put("av_eq_treble", new TrebleHandler(this));

        handlers.put("av_lud", new LoudnessOnHandler(this)); // because this is an existing name supported by MTCManager (for LOUD hardware button)
        handlers.put("av_loudness", new LoudnessHandler(this));

        // configuration
        handlers.put("cfg_maxvolume", new GetMaxVolumeHandler(this));
        handlers.put("cfg_volumerange", new VolumeRangeHandler(this));
        handlers.put("cfg_subwoofer", new SubwooferHandler(this));
        handlers.put("cfg_gps_altmix", new GpsAltMixHandler(this));
        handlers.put("cfg_gps_ontop", new GpsOnTopEnableHandler(this));

        // gps mix support
        handlers.put("av_gps_package", new GpsPackageHandler(this));
        handlers.put("av_gps_monitor", new GpsMonitorHandler(this));
        handlers.put("av_gps_switch", new GpsSwitchHandler(this));
        handlers.put("av_gps_gain", new GpsGainHandler(this));
        handlers.put("av_gps_ontop", new GpsOnTopHandler(this));

        // reject
        ParameterHandler nullHandler = new NullHandler(this);
        handlers.put("av_eq", nullHandler);
    }

    public String getParameters(String keyValue, String defaultValue) {
        String[] parts = Utils.splitKeyValue(keyValue);
        if (parts == null) return defaultValue;
        String key = parts[0];
        String value = null;

        ParameterHandler handler = handlers.get(key);
        if (handler != null) {
            synchronized (lock) {
                checkStateLoaded();
                value = handler.get();
            }
        }

        if (value == null) return defaultValue;

        return value;
    }

    public String setParameters(String keyValue) {
        String[] parts = Utils.splitKeyValue(keyValue);
        if (parts == null) return keyValue;

        String key = parts[0];
        String value = parts[1];

        ParameterHandler handler = handlers.get(key);
        if (handler != null) {
            synchronized (lock) {
                checkStateLoaded();
                value = handler.set(value);
            }
        }

        if (value == null) return null;

        return String.format("%s=%s", key, value);
    }

    public void onMediaPlayerEvent(String callerPackage, int event) {
        if (!shouldCheckPackageSound(callerPackage))
            return;
        boolean aloud = state.gpsState.gpsIsAloud;
        switch (event)
        {
            case MEDIA_STARTED:
                aloud = true;
                break;
            case MEDIA_PAUSED:
            case MEDIA_STOPPED:
            case MEDIA_PLAYBACK_COMPLETE:
            case MEDIA_ERROR:
                aloud = false;
        }
        if (aloud != state.gpsState.gpsIsAloud)
            return;
        state.gpsState.gpsIsAloud = aloud;
        applyState();
    }

    public void onAudioTrackEvent(String callerPackage, int event) {
        if (!shouldCheckPackageSound(callerPackage))
            return;
        boolean aloud = state.gpsState.gpsIsAloud;
        switch (event)
        {
            case AudioTrack.PLAYSTATE_PLAYING:
                aloud = true;
                break;
            case AudioTrack.PLAYSTATE_PAUSED:
            case AudioTrack.PLAYSTATE_STOPPED:
                aloud = false;
        }
        if (aloud != state.gpsState.gpsIsAloud)
            return;
        state.gpsState.gpsIsAloud = aloud;
        applyState();
    }

    private boolean shouldCheckPackageSound(String callerPackage) {
        return state.gpsState.gpsMonitor && state.gpsState.gpsPackage.equals(callerPackage);
    }

    public void applyState() {
        applyState(false);
    }

    public void applyState(boolean forced) {
        if (i2cMode)
            hardware.applyState(state, forced);
        persister.writeState(context, state);
    }

    public void notifyInputChange(){
        context.sendBroadcast(new Intent("com.microntek.inputchange"));
    }

    private void checkStateLoaded() {
        if (!stateLoaded)
        {
            persister.readState(context, state);
            if (i2cMode)
                hardware.applyState(state, true);
            stateLoaded = true;
        }
    }

    private boolean checkHardware() {
        state.HardwareStatus = hardware.CheckHardware();
        return state.HardwareStatus.startsWith("i2c");
    }
}