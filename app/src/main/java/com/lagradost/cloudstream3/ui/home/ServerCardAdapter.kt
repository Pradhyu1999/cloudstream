package com.lagradost.cloudstream3.ui.home

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.ItemServerRatingBinding
import com.lagradost.cloudstream3.utils.UIHelper.toPx

class ServerCardAdapter(
    private val compact: Boolean,
    private val onClick: (ServerCard) -> Unit,
) : RecyclerView.Adapter<ServerCardAdapter.Holder>() {
    private var items: List<ServerCard> = emptyList()

    fun submit(list: List<ServerCard>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemServerRatingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (!compact) {
            binding.root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(private val binding: ItemServerRatingBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(card: ServerCard) {
            val context = binding.root.context
            val observation = card.observation
            val rating = card.rating
            val quality = ServerRatingCalculator.qualitySummary(observation)
            binding.serverName.text = observation.name
            binding.serverKind.text = when (observation.kind) {
                ServerKind.PROVIDER -> context.getString(R.string.server_kind_provider)
                ServerKind.EXTRACTOR -> context.getString(R.string.server_kind_extractor)
            }
            binding.serverQuality.isVisible = quality.label.isNotBlank()
            binding.serverQuality.text = quality.label

            if (!rating.hasData || rating.stars == null || rating.score == null) {
                binding.serverStars.text = context.getString(R.string.server_no_data)
                binding.serverScore.isVisible = false
            } else {
                binding.serverStars.text = ServerRatingCalculator.starGlyphs(rating.stars)
                binding.serverScore.isVisible = true
                binding.serverScore.text = rating.score.toString()
                val color = when {
                    rating.score >= 75 -> R.color.serverRatingGood
                    rating.score >= 45 -> R.color.serverRatingOk
                    else -> R.color.serverRatingPoor
                }
                binding.serverScore.backgroundTintList = ColorStateList.valueOf(context.getColor(color))
            }

            binding.serverMeta.text = metaLine(context, card, quality)
            val ratingLabel = if (!rating.hasData || rating.score == null || rating.stars == null) {
                context.getString(R.string.server_no_data)
            } else {
                context.getString(
                    R.string.server_rating_value,
                    rating.score,
                    ServerRatingCalculator.formatStars(rating.stars),
                )
            }
            val qualityLabel = quality.label.ifBlank { context.getString(R.string.server_quality_unknown) }
            binding.serverCard.contentDescription = context.getString(
                R.string.server_card_description,
                observation.name,
                ratingLabel,
                qualityLabel,
            )
            binding.serverCard.setOnClickListener { onClick(card) }
            if (!compact) {
                val horizontal = 8.toPx
                val vertical = 4.toPx
                (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(
                    horizontal,
                    vertical,
                    horizontal,
                    vertical,
                )
            }
        }
    }
}

private fun metaLine(
    context: android.content.Context,
    card: ServerCard,
    quality: StreamQualitySummary,
): String {
    val rating = card.rating
    val parts = ArrayList<String>(6)
    ServerRatingCalculator.percent(rating.successRate)?.let {
        parts += context.getString(R.string.server_stat_success, it)
    }
    ServerRatingCalculator.percent(rating.failureRate)?.let {
        parts += context.getString(R.string.server_stat_fail, it)
    }
    ServerRatingCalculator.percent(rating.timeoutRate)?.let {
        parts += context.getString(R.string.server_stat_timeout, it)
    }
    rating.averageLatencyMs?.let {
        parts += context.getString(R.string.server_stat_latency, ServerRatingCalculator.formatLatency(it))
    }
    ServerRatingCalculator.percent(rating.playbackErrorRate)?.let { percent ->
        if (percent > 0) parts += context.getString(R.string.server_stat_playback_errors, percent)
    }
    if (card.observation.bufferingEvents > 0) {
        parts += context.getString(R.string.server_stat_rebuffers, card.observation.bufferingEvents)
    }
    quality.codecsLabel?.let { parts += it }
    quality.bitrateLabel?.let { parts += it }
    if (parts.isEmpty()) return context.getString(R.string.server_play_to_rate)
    return parts.joinToString(" · ")
}
