package com.limelight.grid;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;

public abstract class GenericGridAdapter<T> extends BaseAdapter {
    protected final Context context;
    private int layoutId;
    final ArrayList<T> itemList = new ArrayList<>();
    private final LayoutInflater inflater;
    // Track a selected position for UI updates (some activities call setSelectedPosition)
    protected int selectedPosition = -1;

    GenericGridAdapter(Context context, int layoutId) {
        this.context = context;
        this.layoutId = layoutId;

        this.inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    public void setSelectedPosition(int pos) {
        this.selectedPosition = pos;
        // Let views refresh to reflect selection change if they care
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    void setLayoutId(int layoutId) {
        if (layoutId != this.layoutId) {
            this.layoutId = layoutId;

            // Force the view to be redrawn with the new layout
            notifyDataSetInvalidated();
        }
    }

    public void clear() {
        itemList.clear();
    }

    @Override
    public int getCount() {
        return itemList.size();
    }

    @Override
    public Object getItem(int i) {
        return itemList.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    public abstract void populateView(View parentView, ImageView imgView, View spinnerView, TextView txtView, ImageView overlayView, T obj);

    @Override
    public View getView(int i, View convertView, ViewGroup viewGroup) {
        if (convertView == null) {
            convertView = inflater.inflate(layoutId, viewGroup, false);
        }

        ImageView imgView = convertView.findViewById(R.id.grid_image);
        ImageView overlayView = convertView.findViewById(R.id.grid_overlay);
        TextView txtView = convertView.findViewById(R.id.grid_text);
        View spinnerView = convertView.findViewById(R.id.grid_spinner);

        populateView(convertView, imgView, spinnerView, txtView, overlayView, itemList.get(i));

        return convertView;
    }
}
