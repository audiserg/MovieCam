package com.example.moviecam

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.moviecam.model.Statuses

class MainViewModel:ViewModel() {
val status=MutableLiveData<Statuses>().apply { postValue(Statuses.Unknown)}


}