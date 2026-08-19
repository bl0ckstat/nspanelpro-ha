package pro.nspanel.ha2

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import pro.nspanel.ha2.data.SettingsRepository

class MainViewModelFactory(
    private val context: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(SettingsRepository(context.applicationContext)) as T
        }
        error("Unknown ViewModel: ${modelClass.name}")
    }
}
