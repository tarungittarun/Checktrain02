package in.opentrack.india.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import in.opentrack.india.R
import in.opentrack.india.core.RailTime
import in.opentrack.india.model.StopRow
import in.opentrack.india.model.StopStatus
import in.opentrack.india.model.TrainHit

/**
 * Search results. Two rows of information per hit and nothing else — the decision a traveller makes
 * here is "does this train work for me", which needs train identity, times, duration and whether it
 * actually runs on the date they picked.
 */
class TrainHitAdapter(private val onClick: (TrainHit) -> Unit) :
    ListAdapter<TrainHit, TrainHitAdapter.Holder>(DIFF) {

    class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val no: TextView = view.findViewById(R.id.hitNo)
        val name: TextView = view.findViewById(R.id.hitName)
        val times: TextView = view.findViewById(R.id.hitTimes)
        val meta: TextView = view.findViewById(R.id.hitMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_train_hit, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val hit = getItem(position)
        holder.no.text = hit.trainNo
        holder.name.text = prettyName(hit.trainName)
        holder.times.text = hit.prettyTimes()
        holder.meta.text = buildString {
            append(hit.fromCode).append(" → ").append(hit.toCode)
            if (hit.distanceKm > 0) append(" · ").append(hit.distanceKm).append(" km")
            append(" · ").append(RailTime.formatDuration(hit.durationMin))
            val dayDelta = RailTime.dayOffsetOf(hit.boardMinute)
            val boardEpochDay = hit.originDateEpochDay + dayDelta
            val today = RailTime.todayEpochDay()
            append(" · ")
            append(
                when (boardEpochDay - today) {
                    0L -> "today"
                    1L -> "tomorrow"
                    -1L -> "yesterday"
                    else -> RailTime.formatDate(boardEpochDay)
                }
            )
            hit.trainType?.let { append(" · ").append(it.lowercase().replaceFirstChar(Char::uppercase)) }
        }
        holder.itemView.setOnClickListener { onClick(hit) }
    }

    companion object {
        /** "MUMBAI CENTRAL-NEW DELHI RAJDHANI EXPRESS" -> "Mumbai Central–New Delhi Rajdhani Exp" */
        fun prettyName(raw: String): String {
            val cleaned = raw.trim().replace('_', ' ').replace('-', '–')
            return cleaned.lowercase().split(' ').joinToString(" ") { w ->
                if (w.length <= 2) w.uppercase() else w.replaceFirstChar { it.uppercase() }
            }
        }

        private val DIFF = object : DiffUtil.ItemCallback<TrainHit>() {
            override fun areItemsTheSame(a: TrainHit, b: TrainHit) =
                a.trainNo == b.trainNo && a.originDateEpochDay == b.originDateEpochDay &&
                    a.fromCode == b.fromCode && a.toCode == b.toCode

            override fun areContentsTheSame(a: TrainHit, b: TrainHit) = a == b
        }
    }
}

/**
 * The timetable under the live chart. One row per stop, showing scheduled and actual side by side —
 * the comparison the app exists to make.
 */
class StopAdapter(
    private val currentSeq: () -> Int,
    private val highlight: (StopRow) -> Boolean,
) : ListAdapter<StopRow, StopAdapter.Holder>(STOP_DIFF) {

    class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val code: TextView = view.findViewById(R.id.stopCode)
        val name: TextView = view.findViewById(R.id.stopName)
        val sched: TextView = view.findViewById(R.id.stopSched)
        val actual: TextView = view.findViewById(R.id.stopActual)
        val km: TextView = view.findViewById(R.id.stopKm)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_stop, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val s = getItem(position)
        holder.code.text = s.code
        holder.name.text = pretty(s.name)
        holder.sched.text = listOf(
            s.arrivalMin?.let { RailTime.formatRailMinute(it) } ?: "—",
            s.departureMin?.let { RailTime.formatRailMinute(it) } ?: "—"
        ).joinToString(" / ")
        val delay = s.delayArrivalMin
        holder.actual.text = when {
            delay != null && delay != 0 -> RailTime.formatDelay(delay)
            s.status == StopStatus.DEPARTED -> "departed"
            s.status == StopStatus.ARRIVED -> "here"
            s.status == StopStatus.CANCELLED_HERE -> "cancelled"
            else -> "—"
        }
        holder.km.text = if (s.schedKm > 0) s.schedKm.toString() else ""
        val isCurrent = s.seq == currentSeq()
        holder.itemView.isSelected = isCurrent
        holder.itemView.setBackgroundColor(
            when {
                isCurrent -> 0x334CAF50
                highlight(s) -> 0x14FFFFFF
                else -> 0x00000000
            }
        )
    }

    private fun pretty(name: String) = TrainHitAdapter.prettyName(name)

    companion object {
        private val STOP_DIFF = object : DiffUtil.ItemCallback<StopRow>() {
            override fun areItemsTheSame(a: StopRow, b: StopRow) = a.seq == b.seq && a.code == b.code
            override fun areContentsTheSame(a: StopRow, b: StopRow) = a == b
        }
    }
}
