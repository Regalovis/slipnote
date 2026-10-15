package com.notedrop.demo.data

/**
 * 纸条的发送对象。
 *
 * 之前只能发给「最后一个配对的人」，用户没法在写纸条时选人 ——
 * 这是个明显的体验缺陷。收件人应该是一个明确的选择。
 */
sealed interface Recipient {

    /** 一对一发给某个配对用户。 */
    data class Person(val userId: String, val nickname: String) : Recipient

    /**
     * 发到群里。
     *
     * mode 决定玩法：
     * - CHAIN  接力：按成员顺序传给下一位，保留昵称
     * - RANDOM 匿名：随机挑一位群友，双方都不知道对方是谁
     */
    data class Group(
        val groupId: String,
        val name: String,
        val mode: Mode
    ) : Recipient {
        enum class Mode { CHAIN, RANDOM }
    }
}
