package com.helloworld.contract

import com.helloworld.IOUContract
import com.helloworld.IOUState
import com.helloworld.IOU_CONTRACT_ID
import net.corda.testing.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class ContractTests {

    val IOU_CONTRACT_ID = "com.helloworld.IOUContract"
    val partyA = MINI_CORP
    val partyB = MEGA_CORP

    @Before
    fun setup() = setCordappPackages("com.helloworld")

    @After
    fun tearDown() = unsetCordappPackages()

    @Test
    fun `transaction must include a create command`() : Unit {
        val iou = 1
/*        ledger {
            transaction {
                output(IOU_CONTRACT_ID) { IOUState(partyA, partyB, iou) }
                fails()
                command(partyB, partyA) {IOUContract.Create()}
                verifies()
            }
        }*/
    }
}