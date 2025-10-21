package com.roderickqiu.seenot.data

data class MonitoringApp(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val isEnabled: Boolean = true,
    val rules: List<Rule>
)
