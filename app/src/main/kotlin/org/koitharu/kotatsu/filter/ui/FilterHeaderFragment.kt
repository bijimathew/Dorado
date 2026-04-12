package org.koitharu.kotatsu.filter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.databinding.FragmentFilterHeaderBinding
import org.koitharu.kotatsu.filter.data.PersistableFilter
import org.koitharu.kotatsu.filter.ui.model.FilterHeaderModel
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class FilterHeaderFragment : BaseFragment<FragmentFilterHeaderBinding>(), ChipsView.OnChipClickListener,
    ChipsView.OnChipCloseClickListener {

    @Inject
    lateinit var filterHeaderProducer: FilterHeaderProducer

    private var headerJob: Job? = null

    private val filter: FilterCoordinator
        get() = (requireActivity() as FilterCoordinator.Owner).filterCoordinator

    override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentFilterHeaderBinding {
        return FragmentFilterHeaderBinding.inflate(inflater, container, false)
    }

    override fun onViewBindingCreated(binding: FragmentFilterHeaderBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        binding.chipsTags.onChipClickListener = this
        binding.chipsTags.onChipCloseClickListener = this
        headerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                filterHeaderProducer.observeHeader(filter).collect { header ->
                    renderFilterHeader(binding, header)
                }
            }
        }
    }

    override fun onDestroyView() {
        headerJob?.cancel()
        headerJob = null
        viewBinding?.chipsTags?.apply {
            onChipClickListener = null
            onChipCloseClickListener = null
            setChips(emptyList())
        }
        super.onDestroyView()
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

    override fun onChipClick(chip: Chip, data: Any?) {
        when (data) {
            is MangaTag -> filter.toggleTag(data, !chip.isChecked)
            is PersistableFilter -> if (chip.isChecked) {
                filter.reset()
            } else {
                filter.setAdjusted(data.filter)
            }

            is String -> Unit
            null -> router.showTagsCatalogSheet(excludeMode = false)
        }
    }

    override fun onChipCloseClick(chip: Chip, data: Any?) {
        when (data) {
            is String -> if (data == filter.snapshot().listFilter.author) {
                filter.setAuthor(null)
            } else {
                filter.setQuery(null)
            }

            is ContentRating -> filter.toggleContentRating(data, false)
            is Demographic -> filter.toggleDemographic(data, false)
            is ContentType -> filter.toggleContentType(data, false)
            is MangaState -> filter.toggleState(data, false)
            is Locale -> filter.setLocale(null)
            is Int -> filter.setYear(YEAR_UNKNOWN)
            is IntRange -> filter.setYearRange(YEAR_UNKNOWN, YEAR_UNKNOWN)
        }
    }

}

private fun renderFilterHeader(binding: FragmentFilterHeaderBinding, header: FilterHeaderModel) {
    val chips = header.chips
    if (chips.isEmpty()) {
        binding.chipsTags.setChips(emptyList())
        binding.root.isVisible = false
        return
    }
    binding.chipsTags.setChips(chips)
    binding.root.isVisible = true
    if (binding.root.context.isAnimationsEnabled) {
        binding.scrollView.smoothScrollTo(0, 0)
    } else {
        binding.scrollView.scrollTo(0, 0)
    }
}
