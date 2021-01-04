package com.example.moviecam.model

enum class Statuses(val displayed:String) {
    Unknown("Неизвестный"),
    Ready("Готовность к записи"),
    OnRec("Запись"),
    RecStopped("Запись выполнена")
    ;

    fun getText()=this.displayed
}