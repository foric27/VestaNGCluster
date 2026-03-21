package ru.foric27.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootNetUtilTest {

    @Test
    fun `link up определяется по lower_up`() {
        val output = "7: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc fq_codel state UP mode DEFAULT group default qlen 1000"

        assertTrue(RootNetUtil.isIfaceLinkUp(output))
    }

    @Test
    fun `link up определяется по carrier`() {
        val output = "7: eth0: <BROADCAST,MULTICAST,UP> mtu 1500 qdisc fq_codel state DOWN mode DEFAULT group default qlen 1000\n1"

        assertTrue(RootNetUtil.isIfaceLinkUp(output))
    }

    @Test
    fun `link down определяется при отсутствии lower_up и carrier`() {
        val output = "7: eth0: <BROADCAST,MULTICAST,UP> mtu 1500 qdisc noop state DOWN mode DEFAULT group default qlen 1000\n0"

        assertFalse(RootNetUtil.isIfaceLinkUp(output))
    }
}
