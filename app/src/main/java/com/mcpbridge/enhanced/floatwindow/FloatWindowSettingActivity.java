package com.mcpbridge.enhanced.floatwindow;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.SeekBar;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.switchmaterial.SwitchMaterial;

import com.mcpbridge.enhanced.R;

/**
 * 悬浮窗设置 Activity
 */
public class FloatWindowSettingActivity extends Activity {

    private SwitchMaterial switchFloatWindow;
    private SeekBar seekBarOpacity;
    private SwitchMaterial switchAutoEdge;
    private FloatWindowManager floatWindowManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_float_setting);

        floatWindowManager = FloatWindowManager.getInstance(this);

        switchFloatWindow = findViewById(R.id.switchFloatWindow);
        seekBarOpacity = findViewById(R.id.seekBarOpacity);
        switchAutoEdge = findViewById(R.id.switchAutoEdge);

        // 加载当前设置
        switchFloatWindow.setChecked(floatWindowManager.isEnabled());
        seekBarOpacity.setProgress(floatWindowManager.getOpacity());
        switchAutoEdge.setChecked(floatWindowManager.isAutoEdge());

        // 悬浮窗开关
        switchFloatWindow.setOnCheckedChangeListener((buttonView, isChecked) -> {
            floatWindowManager.setEnabled(isChecked);
            if (isChecked) {
                checkOverlayPermissionAndShow();
            } else {
                floatWindowManager.hide();
            }
        });

        // 透明度滑块
        seekBarOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    floatWindowManager.updateOpacity(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 自动吸边开关
        switchAutoEdge.setOnCheckedChangeListener((buttonView, isChecked) -> {
            floatWindowManager.setAutoEdge(isChecked);
            if (isChecked && floatWindowManager.isShowing()) {
                floatWindowManager.checkAndSnap();
            }
        });
    }

    private void checkOverlayPermissionAndShow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要悬浮窗权限")
                    .setMessage("请在设置中允许本应用显示悬浮窗")
                    .setPositiveButton("去设置", (d, w) -> {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName())
                        );
                        startActivity(intent);
                    })
                    .setNegativeButton("取消", (d, w) -> {
                        switchFloatWindow.setChecked(false);
                        floatWindowManager.setEnabled(false);
                    })
                    .show();
        } else {
            floatWindowManager.show();
        }
    }

    public static void open(Context context) {
        Intent intent = new Intent(context, FloatWindowSettingActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}