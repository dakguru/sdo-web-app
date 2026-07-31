package com.karursdo.ui.directory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.db.EmployeeEntity
import com.karursdo.data.db.OfficeSummary
import com.karursdo.data.db.OutsiderEntity
import com.karursdo.data.repo.DirectoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TypeFilter { ALL, DS, GDS }

data class GlobalHit(val employee: EmployeeEntity, val phone: String?)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class DirectoryViewModel @Inject constructor(
    private val repo: DirectoryRepository
) : ViewModel() {

    val officeSearch = MutableStateFlow("")
    val typeFilter = MutableStateFlow(TypeFilter.ALL)
    val outsiderSearch = MutableStateFlow("")
    val globalSearch = MutableStateFlow("")

    private val allOffices: StateFlow<List<OfficeSummary>> =
        repo.employeeDao.officeSummaries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalOfficeCount: StateFlow<Int> = allOffices
        .combine(MutableStateFlow(Unit)) { l, _ -> l.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Filtered office list, mirroring web filters (search + DS/GDS tab). */
    val offices: StateFlow<List<OfficeSummary>> =
        combine(allOffices, officeSearch.debounce(150), typeFilter) { list, q, tf ->
            list.filter { o ->
                val matchQ = q.isBlank() ||
                    (o.officeName ?: "").contains(q, true) || o.officeId.contains(q, true)
                val matchT = when (tf) {
                    TypeFilter.ALL -> true
                    TypeFilter.DS -> o.dsCount > 0
                    TypeFilter.GDS -> o.gdsCount > 0
                }
                matchQ && matchT
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val outsiders: StateFlow<List<OutsiderEntity>> =
        outsiderSearch.debounce(150).flatMapLatest { q ->
            if (q.isBlank()) repo.outsiderDao.all() else repo.outsiderDao.search(q)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val outsiderCount: StateFlow<Int> =
        repo.outsiderDao.count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val globalHits = MutableStateFlow<List<GlobalHit>>(emptyList())

    init {
        viewModelScope.launch {
            globalSearch.debounce(200).collect { q ->
                globalHits.value =
                    if (q.length < 2) emptyList()
                    else repo.employeeDao.search(q).map {
                        GlobalHit(it, repo.mobileDao.phoneFor(it.employeeId))
                    }
            }
        }
    }
}
