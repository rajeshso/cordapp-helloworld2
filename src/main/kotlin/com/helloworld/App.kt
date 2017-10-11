package com.helloworld

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.Arrays.asList
import java.util.function.Function
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import kotlin.reflect.jvm.jvmName

// *****************
// * API Endpoints *
// *****************
@Path("template")
class TemplateApi(val services: CordaRPCOps) {
    // Accessible at /api/template/templateGetEndpoint.
    @GET
    @Path("templateGetEndpoint")
    @Produces(MediaType.APPLICATION_JSON)
    fun templateGetEndpoint(): Response {
        return Response.ok(mapOf("message" to "Helloworld GET endpoint.")).build()
    }
}

// *****************
// * Contract Code *
// *****************
// This is used to identify our contract when building a transaction
val IOU_CONTRACT_ID = "com.helloworld.IOUContract"

open class IOUContract : Contract {
    //Command indicate the transaction’s intent, allowing us to perform different verification given the situation.
    class Create : CommandData

    // The verify() function of the contract for each of the transaction's input and output states must not throw an
    // exception for a transaction to be considered valid.
    override fun verify(tx: LedgerTransaction) {
        // Verification logic goes here - An IOUState can only be created, not transferred or redeemed
        val command = tx.commands.requireSingleCommand<Create>()

        requireThat {
            //A transaction involving IOUs must consume zero inputs, and create one output of type IOUState
            "A transaction involving IOUs must consume zero inputs".using(tx.inputs.isEmpty())
            "create one output of type IOUState" using (tx.outputs.size == 1)

            //The transaction should also include a Create command, indicating the transaction’s intent
            "The transaction should also include a Create command" using (tx.commands.single().value is Create)

            val outputStateTx  = tx.outputs.single().data as IOUState
            /* For the transactions’s output IOU state:
            Its value must be non-negative
            The lender and the borrower cannot be the same entity
                    The IOU’s lender must sign the transaction
            */
            "transactions’s output IOU state - Its value must be non-negative" using (outputStateTx.value > 0)
            "transactions’s output IOU state - The lender and the borrower cannot be the same entity" using (outputStateTx.lender != outputStateTx.borrower)
            "transactions’s output IOU state - The IOU’s lender must sign the transaction - firstly, a signer should be present" using (command.signers.toSet().size == 2)
            "transactions’s output IOU state - The IOU’s lender must sign the transaction" using (command.signers.contains(outputStateTx.lender.owningKey))

            //Two Party Flows
            "transactions involving an IOUState require the borrower’s signature (as well as the lender’s) to become valid ledger updates" using (command.signers.toSet().size == 2)
            "the borrowers and lenders must be signers" using (command.signers.containsAll(listOf(outputStateTx.borrower.owningKey, outputStateTx.lender.owningKey)))
        }
    }

    val legalContractReference = SecureHash.zeroHash
}

// *********
// * State *
// *********
class IOUState(val lender: Party,
               val borrower: Party,
               val value: Int) : ContractState {
    // ContractState may have a Contract - The contract that imposes constraints on how this state can evolve over time.
    val contract = IOUContract()
    // the list of entities that have to approve state changes such as changing the state’s notary or upgrading the state’s contract
    override val participants: List<AbstractParty> get() = listOf(lender, borrower)
}

// *********
// * Flows *
// *********
@InitiatingFlow
@StartableByRPC
class IOUFlow(val iouValue: Int, val otherParty: Party) : FlowLogic<Unit>() {

    override val progressTracker: ProgressTracker = ProgressTracker()

    @Suspendable
    override fun call() {
        // We retrieve the required identities from  the network map
        val me = ourIdentity
        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        // We create a transaction builder
        val txBuilder = TransactionBuilder(notary = notary)
        // We add the items to the builder
        val outputState = IOUState(lender = me, borrower = otherParty, value = iouValue)
        //val outputContract = IOUContract::class.jvmName
        val command = Command(IOUContract.Create(), listOf(me.owningKey, otherParty.owningKey))
        txBuilder
                .addCommand(command)
                .addOutputState(outputState, IOU_CONTRACT_ID)

        // Verifying the transaction
        txBuilder.verify(serviceHub)
        // Signing the transaction
        val partiallySignedTx = serviceHub.signInitialTransaction(txBuilder)

        //Two party Flow - the lender requires the borrower’s agreement before they can issue an IOU onto the ledger.
        // Create session with other party
        val session = initiateFlow(otherParty)
        // Obtain the counter party's signature
        val fullySignedTx = subFlow(CollectSignaturesFlow(partiallySignedTx= partiallySignedTx, sessionsToCollectFrom= listOf(session), progressTracker = CollectSignaturesFlow.tracker()))

        // Finalizing the transaction
        subFlow(FinalityFlow(fullySignedTx))
        Unit
    }
}

@InitiatedBy(IOUFlow::class)
class IOUFlowResponder(val otherSideSession: FlowSession) : FlowLogic<Unit>() {
    override fun call() {
        //create an object of SignTransactionFlow
        val signTxFlow = object : SignTransactionFlow(otherSideSession= otherSideSession,
                progressTracker = SignTransactionFlow.tracker()) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This should be a valid iou state only" using (output is IOUState)
                val iou = output as IOUState
                "The iouValue should not be too high" using (iou.value < 100)
            }
        }
        subFlow(signTxFlow)
    }
}

// Serialization whitelist (only needed for 3rd party classes, but we use a local example here).
class TemplateSerializationWhitelist : SerializationWhitelist {
    override val whitelist: List<Class<*>> = listOf(TemplateData::class.java)
}

// Not annotated with @CordaSerializable just for use with manual whitelisting above.
data class TemplateData(val payload: String)

class TemplateWebPlugin : WebServerPluginRegistry {
    // A list of classes that expose web JAX-RS REST APIs.
    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::TemplateApi))
    //A list of directories in the resources directory that will be served by Jetty under /web.
    // This template's web frontend is accessible at /web/template.
    override val staticServeDirs: Map<String, String> = mapOf(
            // This will serve the templateWeb directory in resources to /web/template
            "template" to javaClass.classLoader.getResource("templateWeb").toExternalForm()
    )
}
