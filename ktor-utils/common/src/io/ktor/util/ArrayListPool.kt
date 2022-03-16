/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.util

import io.ktor.utils.io.pool.*

private const val ARRAY_LIST_POOL_CAPACITY: Int = 16 * 1024
private const val ARRAY_DEFAULT_SIZE: Int = 16

/**
 * ArrayList pool for internal usage.
 */
@InternalAPI
public object ArrayListPool : DefaultPool<ArrayList<Any>>(ARRAY_LIST_POOL_CAPACITY) {
    override fun produceInstance(): ArrayList<Any> = ArrayList(ARRAY_DEFAULT_SIZE)
}

/**
 * Borrow ArrayList<T> instance.
 */
@Suppress("UNCHECKED_CAST")
@InternalAPI
public fun <T> ArrayListPool.borrowArray(): ArrayList<T> = borrow() as ArrayList<T>

/**
 * Recycle ArrayList<T> instance.
 */
@InternalAPI
public fun <T> ArrayListPool.recycle(instance: ArrayList<T>) {
    @Suppress("UNCHECKED_CAST")
    recycle(instance as ArrayList<Any>)
}
