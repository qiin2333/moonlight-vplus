package com.limelight.preferences;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

import androidx.preference.PreferenceDialogFragmentCompat;

import com.limelight.R;

public class CustomResolutionsPreferenceDialogFragment extends PreferenceDialogFragmentCompat {

    public static CustomResolutionsPreferenceDialogFragment newInstance(String key) {
        CustomResolutionsPreferenceDialogFragment fragment = new CustomResolutionsPreferenceDialogFragment();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        fragment.setArguments(args);
        return fragment;
    }

    private CustomResolutionsPreference getPref() {
        return (CustomResolutionsPreference) getPreference();
    }

    @Override
    protected View onCreateDialogView(android.content.Context context) {
        CustomResolutionsPreference pref = getPref();

        LinearLayout body = createMainLayout(context);
        ListView list = createListView(context, pref);
        View inputRow = createInputRow(context, pref);

        body.addView(list);
        body.addView(inputRow);

        return body;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        getPref().loadStoredResolutions();
    }

    private LinearLayout createMainLayout(android.content.Context context) {
        LinearLayout body = new LinearLayout(context);

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int dialogWidth = Math.min((int) (screenWidth * 0.8), dpToPx(context, 400));

        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                dialogWidth,
                AbsListView.LayoutParams.WRAP_CONTENT
        );
        layoutParams.gravity = Gravity.CENTER;
        body.setLayoutParams(layoutParams);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dpToPx(context, 16), dpToPx(context, 16), dpToPx(context, 16), dpToPx(context, 16));

        return body;
    }

    private ListView createListView(android.content.Context context, CustomResolutionsPreference pref) {
        ListView list = new ListView(context);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        );
        list.setLayoutParams(layoutParams);
        list.setAdapter(pref.adapter);
        list.setDividerHeight(dpToPx(context, 1));
        list.setDivider(context.getResources().getDrawable(android.R.color.darker_gray));
        return list;
    }

    private View createInputRow(android.content.Context context, CustomResolutionsPreference pref) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View inputRow = inflater.inflate(R.layout.custom_resolutions_form, null);

        EditText widthField = inputRow.findViewById(R.id.custom_resolution_width_field);
        EditText heightField = inputRow.findViewById(R.id.custom_resolution_height_field);
        Button addButton = inputRow.findViewById(R.id.add_resolution_button);

        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AbsListView.LayoutParams.WRAP_CONTENT
        );
        layoutParams.topMargin = dpToPx(context, 16);
        inputRow.setLayoutParams(layoutParams);

        addButton.setOnClickListener(view -> pref.onSubmitResolution(widthField, heightField));
        heightField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                pref.onSubmitResolution(widthField, heightField);
                return true;
            }
            return false;
        });

        return inputRow;
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        StreamSettings settingsActivity = (StreamSettings) requireActivity();
        settingsActivity.reloadSettings();
    }

    private static int dpToPx(android.content.Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
