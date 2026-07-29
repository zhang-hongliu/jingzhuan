package com.example.migratable

import kotlin.random.Random

/** 从消息库中随机取一条 */
object MessageProvider {
    fun random(): String = Messages.LIST[Random.nextInt(Messages.LIST.size)]
}
