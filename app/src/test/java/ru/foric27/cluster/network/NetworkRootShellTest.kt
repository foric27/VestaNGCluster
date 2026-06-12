package ru.foric27.cluster.network

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NetworkRootShellTest {

    private val shell = NetworkRootShell()

    @Test
    fun `validateNoCommandInjection rejects every blocked shell token`() {
        val cases = listOf(
            ";" to "ip addr add 192.168.1.1/24 dev eth0 ; rm -rf /",
            "`" to "ip addr add 192.168.1.1/24 dev eth0 `id`",
            "$(" to "ip addr add $(id) dev eth0",
            "&&" to "ip addr add 192.168.1.1/24 dev eth0 && rm -rf /",
            "||" to "iptables -A OUTPUT -j MARK --set-mark 0x1 || reboot",
            "|" to "ip route get 192.168.1.1 | cat /etc/passwd",
            ">" to "ip route get 192.168.1.1 > /tmp/out",
            "<" to "ip route get 192.168.1.1 < /tmp/in",
            "&" to "ip route replace default via 192.168.1.1 &",
            "#" to "ip addr add 192.168.1.1/24 dev eth0 # comment",
        )

        cases.forEachIndexed { index, (token, command) ->
            val error = expectIllegalArgument {
                shell.validateNoCommandInjection(command = command, index = index)
            }

            assertEquals(
                "Опасные shell-конструкции запрещены для network-команд: #${index + 1} (token=$token)",
                error.message,
            )
        }
    }

    @Test
    fun `validateNoCommandInjection allows simple network commands`() {
        listOf(
            "ip addr add 192.168.1.1/24 dev eth0",
            "ip route replace default via 192.168.1.1",
            "iptables -t mangle -A OUTPUT -j MARK --set-mark 0x1",
        ).forEachIndexed { index, command ->
            shell.validateNoCommandInjection(command = command, index = index)
        }
    }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException {
        return try {
            block()
            fail("Expected IllegalArgumentException")
            throw AssertionError("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
    }
}
