package com.droidrocks.ondeviceai;

import android.os.Bundle;
import android.widget.ImageButton;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.droidrocks.ondeviceai.adapter.ModelsPagerAdapter;
import com.droidrocks.ondeviceai.databinding.ActivityModelListBinding;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class ModelListActivity extends BaseActivity {

    private static final String TAG = "ModelListActivity";
    private ActivityModelListBinding binding;
    
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private ModelsPagerAdapter pagerAdapter;

    private final String[] tabTitles = new String[2];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityModelListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applyEdgeToEdgeInsets(binding.getRoot());




        // Initialize tab titles
        tabTitles[0] = getString(R.string.tab_downloaded);
        tabTitles[1] = getString(R.string.tab_available);

        initViews();
        setupTabs();
    }

    private void initViews() {
        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);
        ImageButton btnBack = findViewById(R.id.btnBack);
        
        btnBack.setOnClickListener(v -> finish());
    }

    private void setupTabs() {
        pagerAdapter = new ModelsPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        
        // Connect TabLayout with ViewPager2
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            tab.setText(tabTitles[position]);
        }).attach();
    }
}
