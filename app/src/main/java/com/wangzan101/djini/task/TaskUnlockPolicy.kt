package com.wangzan101.djini.task

import com.wangzan101.djini.core.run.ChannelPeer
import com.wangzan101.djini.core.run.RunOrigin
import com.wangzan101.djini.wechat.WechatChannel

/** 开跑时只为定时任务或已授权的微信任务提供解锁；实际解锁时仍重新读取微信授权。 */
internal fun taskUnlockAuthorization(
    origin: RunOrigin,
    peer: ChannelPeer?,
    canUnlockWechat: (ChannelPeer?) -> Boolean,
): (() -> Boolean)? = when {
    origin == RunOrigin.Trigger -> ({ true })
    origin == RunOrigin.Channel && peer?.channelId == WechatChannel.ID && canUnlockWechat(peer) ->
        ({ canUnlockWechat(peer) })
    else -> null
}
