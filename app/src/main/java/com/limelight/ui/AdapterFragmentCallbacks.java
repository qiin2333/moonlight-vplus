package com.limelight.ui;

import android.view.View;

public interface AdapterFragmentCallbacks {
    int getAdapterFragmentLayoutId();
    // Generalized to accept any View (RecyclerView or AbsListView). Implementations
    // should check the runtime type if necessary.
    void receiveAbsListView(View gridView);
}
