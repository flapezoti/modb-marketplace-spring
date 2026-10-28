package marketplace.coordinator;

import dk.ku.di.dms.vms.coordinator.Coordinator;
import dk.ku.di.dms.vms.coordinator.infra.AbstractHttpHandler;
import dk.ku.di.dms.vms.coordinator.transaction.TransactionInput;
import dk.ku.di.dms.vms.web_common.IHttpHandler;

/**
 * HTTP ingestion + TID polling for the standalone coordinator, mirroring the original
 * implementation's proxy (sources/modb/marketplace/proxy's ProxyHttpServerAsyncJdk) so the same
 * benchmark driver can run unchanged against both.
 *
 * PATCH submits an update_price transaction, PUT an update_product one; the request body is
 * the transaction's input event payload, passed through as-is. Ingestion is fire-and-forget:
 * the HTTP response only means the input was queued, not committed.
 *
 * GET /status/committed and /status/submitted (last committed / last submitted TID, 8-byte
 * little-endian long) are inherited from AbstractHttpHandler. They are only served for
 * "Accept: application/octet-stream"; ModbHttpServer routes any other Accept to getAsJson,
 * which is not implemented here, so the response is a 404.
 */
public final class CoordinatorProxyHttpHandler extends AbstractHttpHandler implements IHttpHandler {

    public CoordinatorProxyHttpHandler(Coordinator coordinator) {
        super(coordinator);
    }

    @Override
    public void patch(String uri, String body) {
        this.submit("update_price", body);
    }

    @Override
    public void put(String uri, String body) {
        this.submit("update_product", body);
    }

    private void submit(String transaction, String payload) {
        TransactionInput.Event eventPayload = new TransactionInput.Event(transaction, payload);
        this.coordinator.queueTransactionInput(new TransactionInput(transaction, eventPayload));
    }

}
