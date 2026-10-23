package com.lagradost.cloudstream3.ui.home

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.chip.Chip
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentServerRatingsBinding
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.popCurrentPage

class ServerRatingsFragment : BaseFragment<FragmentServerRatingsBinding>(
    BindingCreator.Inflate(FragmentServerRatingsBinding::inflate)
) {
    private val homeViewModel: HomeViewModel by activityViewModels()
    private var sort = ServerSort.RATING
    private var filter = ServerFilter.ALL

    private val cards = ServerCardAdapter(compact = false) { card ->
        if (card.observation.kind != ServerKind.PROVIDER) return@ServerCardAdapter
        val api = getApiFromNameNull(card.observation.name) ?: return@ServerCardAdapter
        homeViewModel.loadAndCancel(api.name, forceReload = true, fromUI = true)
        showToast(getString(R.string.server_home_source_set, api.name), Toast.LENGTH_SHORT)
        activity?.popCurrentPage()
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padLeft = isLayout(TV or EMULATOR),
        )
    }

    override fun onBindingCreated(binding: FragmentServerRatingsBinding, savedInstanceState: Bundle?) {
        binding.serverRatingsBack.setOnClickListener {
            activity?.popCurrentPage()
        }
        clearFocusBlock(binding)
        binding.serverRatingsList.adapter = cards
        binding.serverRatingsList.setLinearListLayout(
            isHorizontal = false,
            nextLeft = R.id.nav_rail_view,
            nextRight = FOCUS_SELF,
            nextUp = R.id.server_filter_all,
            nextDown = FOCUS_SELF,
        )
        binding.serverSortRating.isChecked = true
        binding.serverFilterAll.isChecked = true
        binding.serverSortGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            sort = when (checkedIds.firstOrNull()) {
                R.id.server_sort_quality -> ServerSort.QUALITY
                R.id.server_sort_name -> ServerSort.NAME
                R.id.server_sort_latency -> ServerSort.LATENCY
                else -> ServerSort.RATING
            }
            render()
        }
        binding.serverFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when (checkedIds.firstOrNull()) {
                R.id.server_filter_rated -> ServerFilter.RATED
                R.id.server_filter_no_data -> ServerFilter.UNRATED
                R.id.server_filter_providers -> ServerFilter.PROVIDERS
                R.id.server_filter_extractors -> ServerFilter.EXTRACTORS
                else -> ServerFilter.ALL
            }
            render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val binding = binding ?: return
        val names = context?.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
            ?.map { it.name }
            .orEmpty()
        val assembled = ServerRatingCalculator.assemble(
            providerNames = names,
            snapshot = ServerRatings.snapshot(),
            sort = sort,
            filter = filter,
        )
        binding.serverRatingsEmpty.isVisible = assembled.isEmpty()
        binding.serverRatingsList.isVisible = assembled.isNotEmpty()
        cards.submit(assembled)
    }

    private fun clearFocusBlock(binding: FragmentServerRatingsBinding) {
        val chips = listOf<Chip>(
            binding.serverSortRating,
            binding.serverSortQuality,
            binding.serverSortName,
            binding.serverSortLatency,
            binding.serverFilterAll,
            binding.serverFilterRated,
            binding.serverFilterNoData,
            binding.serverFilterProviders,
            binding.serverFilterExtractors,
        )
        chips.forEach { chip ->
            chip.tag = null
            chip.isFocusable = true
        }
    }
}
