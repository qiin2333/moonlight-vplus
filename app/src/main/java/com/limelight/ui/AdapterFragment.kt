@file:Suppress("DEPRECATION")

package com.limelight.ui

import android.app.Activity
import android.app.Fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import com.limelight.R

class AdapterFragment : Fragment() {
    private lateinit var callbacks: AdapterFragmentCallbacks

    @Deprecated("Deprecated in Java")
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        callbacks = activity as AdapterFragmentCallbacks
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(callbacks.getAdapterFragmentLayoutId(), container, false)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        // Pass the view (could be a GridView or RecyclerView) to the activity
        callbacks.receiveAbsListView(view!!.findViewById(R.id.fragmentView))
    }
}
