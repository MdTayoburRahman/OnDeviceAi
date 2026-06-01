package com.droidrocks.ondeviceai;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;

import com.droidrocks.ondeviceai.databinding.ActivitySettingsBinding;

public class SettingsActivity extends BaseActivity {

    private ActivitySettingsBinding binding;
    private SharedPreferences prefs;
    private String currentTheme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applyEdgeToEdgeInsets(binding.getRoot());

        prefs = App.getPrefs(this);

        binding.btnBack.setOnClickListener(v -> finish());
        setupThemeSection();
        setupSliders();
    }

    private void setupThemeSection() {
        currentTheme = prefs.getString(App.KEY_THEME, App.THEME_SYSTEM);

        switch (currentTheme) {
            case App.THEME_LIGHT:
                binding.rbThemeLight.setChecked(true);
                break;
            case App.THEME_DARK:
                binding.rbThemeDark.setChecked(true);
                break;
            default:
                binding.rbThemeSystem.setChecked(true);
                break;
        }

        binding.rgTheme.setOnCheckedChangeListener((group, checkedId) -> {
            String newTheme = App.THEME_SYSTEM;
            if (checkedId == R.id.rbThemeLight) {
                newTheme = App.THEME_LIGHT;
            } else if (checkedId == R.id.rbThemeDark) {
                newTheme = App.THEME_DARK;
            }

            if (!newTheme.equals(currentTheme)) {
                currentTheme = newTheme;
                prefs.edit().putString(App.KEY_THEME, newTheme).apply();
                applyTheme(newTheme);
            }
        });
    }

    private void applyTheme(String theme) {
        int nightMode;
        switch (theme) {
            case App.THEME_LIGHT:
                nightMode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case App.THEME_DARK:
                nightMode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(nightMode);
        Toast.makeText(this, R.string.theme_restart_msg, Toast.LENGTH_SHORT).show();
    }

    private void setupSliders() {
        float temp = prefs.getFloat(App.KEY_TEMPERATURE, 0.4f);
        float topP = prefs.getFloat(App.KEY_TOP_P, 0.95f);
        int maxTokens = prefs.getInt(App.KEY_MAX_TOKENS, 150);

        binding.sliderSettingsTemp.setValue(temp);
        binding.tvSettingsTempValue.setText(String.format("%.2f", temp));
        binding.sliderSettingsTopP.setValue(topP);
        binding.tvSettingsTopPValue.setText(String.format("%.2f", topP));
        binding.sliderSettingsMaxTokens.setValue(maxTokens);
        binding.tvSettingsMaxTokensValue.setText(String.valueOf(maxTokens));

        binding.sliderSettingsTemp.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                binding.tvSettingsTempValue.setText(String.format("%.2f", value));
                prefs.edit().putFloat(App.KEY_TEMPERATURE, value).apply();
            }
        });

        binding.sliderSettingsTopP.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                binding.tvSettingsTopPValue.setText(String.format("%.2f", value));
                prefs.edit().putFloat(App.KEY_TOP_P, value).apply();
            }
        });

        binding.sliderSettingsMaxTokens.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                binding.tvSettingsMaxTokensValue.setText(String.valueOf((int) value));
                prefs.edit().putInt(App.KEY_MAX_TOKENS, (int) value).apply();
            }
        });
    }
}
