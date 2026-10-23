package com.lagradost.cloudstream3.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.HomepageServerSectionBinding
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.toPx

/**
 * First row of the home screen. One section, with a horizontal list of servers inside it.
 */
class ServerStripAdapter(
    private val providerNames: () -> List<String>,
    private val onOpenAll: () -> Unit,
    private val onProviderSelected: (String) -> Unit,
) : RecyclerView.Adapter<ServerStripAdapter.Holder>() {
    private var holder: Holder? = null

    fun refresh() {
        holder?.bind() ?: notifyDataSetChanged()
    }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = HomepageServerSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        this.holder = holder
        holder.bind()
    }

    override fun onViewRecycled(holder: Holder) {
        if (this.holder === holder) this.holder = null
        super.onViewRecycled(holder)
    }

    inner class Holder(private val binding: HomepageServerSectionBinding) : RecyclerView.ViewHolder(binding.root) {
        private val cards = ServerCardAdapter(compact = true) { card ->
            if (card.observation.kind == ServerKind.PROVIDER) {
                onProviderSelected(card.observation.name)
            } else {
                onOpenAll()
            }
        }

        fun bind() {
            val context = binding.root.context
            if (!isLayout(PHONE)) {
                binding.serverSectionTitle.setPaddingRelative(
                    binding.serverSectionTitle.paddingStart,
                    binding.serverSectionTitle.paddingTop,
                    120.toPx,
                    binding.serverSectionTitle.paddingBottom,
                )
            }
            binding.serverSectionTitle.nextFocusUpId = R.id.home_change_api
            binding.serverSectionTitle.setOnClickListener { onOpenAll() }
            binding.serverSectionRow.adapter = cards
            binding.serverSectionRow.setLinearListLayout(
                isHorizontal = true,
                nextLeft = R.id.nav_rail_view,
                nextRight = FOCUS_SELF,
                nextUp = R.id.server_section_title,
            )
            val assembled = ServerRatingCalculator.assemble(
                providerNames = providerNames(),
                snapshot = ServerRatings.snapshot(),
                sort = ServerSort.RATING,
                filter = ServerFilter.ALL,
            )
            binding.serverSectionEmpty.isVisible = assembled.isEmpty()
            binding.serverSectionRow.isVisible = assembled.isNotEmpty()
            cards.submit(assembled)
        }
    }
}
