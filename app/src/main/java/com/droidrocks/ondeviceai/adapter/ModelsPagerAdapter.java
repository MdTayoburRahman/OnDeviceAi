package com.droidrocks.ondeviceai.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.droidrocks.ondeviceai.fragment.AvailableModelsFragment;
import com.droidrocks.ondeviceai.fragment.DownloadedModelsFragment;

/**
 * ViewPager adapter for the model tabs.
 */
public class ModelsPagerAdapter extends FragmentStateAdapter {

    private DownloadedModelsFragment downloadedFragment;
    private AvailableModelsFragment availableFragment;

    public ModelsPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) {
            downloadedFragment = DownloadedModelsFragment.newInstance();
            return downloadedFragment;
        } else {
            availableFragment = AvailableModelsFragment.newInstance();
            return availableFragment;
        }
    }

    @Override
    public int getItemCount() {
        return 2;
    }

    public DownloadedModelsFragment getDownloadedFragment() {
        return downloadedFragment;
    }

    public AvailableModelsFragment getAvailableFragment() {
        return availableFragment;
    }
}

