package com.kmou.capstone_sensor

import android.R
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class LapTimeAdapter(private val context: Context, private val lapTimes: List<String>) :
    ArrayAdapter<String?>(
        context, R.layout.simple_list_item_1, lapTimes
    ) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val rowView = inflater.inflate(R.layout.simple_list_item_1, parent, false)

        val textView = rowView.findViewById<View>(R.id.text1) as TextView
        textView.text = lapTimes[position]

        return rowView
    }
}
