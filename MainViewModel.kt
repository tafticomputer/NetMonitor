package com.example.netmonitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.netmonitor.data.AppDatabase
import com.example.netmonitor.data.AppDomainsGroup
import com.example.netmonitor.data.AppUsageInfo
import com.example.netmonitor.util.UsageStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UsagePeriod(val label: String, val millis: Long) {
    TODAY("امروز", 24L * 60 * 60 * 1000),
    WEEK("۷ روز اخیر", 7L * 24 * 60 * 60 * 1000),
    MONTH("۳۰ روز اخیر", 30L * 24 * 60 * 60 * 1000)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).domainConnectionDao()

    private val _usageList = MutableStateFlow<List<AppUsageInfo>>(emptyList())
    val usageList: StateFlow<List<AppUsageInfo>> = _usageList

    private val _selectedPeriod = MutableStateFlow(UsagePeriod.TODAY)
    val selectedPeriod: StateFlow<UsagePeriod> = _selectedPeriod

    val domainGroups: StateFlow<List<AppDomainsGroup>> =
        dao.observeGrouped().stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList()
        )

    fun setPeriod(period: UsagePeriod) {
        _selectedPeriod.value = period
        refreshUsage()
    }

    fun refreshUsage() {
        viewModelScope.launch {
            val period = _selectedPeriod.value
            val end = System.currentTimeMillis()
            val start = end - period.millis
            val result = withContext(Dispatchers.IO) {
                UsageStatsHelper.getAppDataUsage(getApplication(), start, end)
            }
            _usageList.value = result
        }
    }

    fun clearDomainHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearAll()
        }
    }
}
