package com.trading.api;

import com.trading.aggregator.AggregatorManager;
import com.trading.aggregator.SymbolAggregator;
import com.trading.buffer.ChronicleRingBuffer;
import com.trading.buffer.RingBuffer;
import com.trading.model.BatchRequest;
import com.trading.model.Stats;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.Consumes;
import javax.ws.rs.QueryParam;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.util.function.Supplier;

@Path("/")
public class TradingResource {

    // TODO: should be increased for further production usage
    private static final int GLOBAL_CAPACITY = 1_000_000;


    private final AggregatorManager manager;


    public TradingResource() {
        Supplier<RingBuffer> bufferSupplier = () -> new ChronicleRingBuffer(GLOBAL_CAPACITY);
        this.manager = new AggregatorManager(bufferSupplier, GLOBAL_CAPACITY);
    }

    /**
     * POST /add_batch/
     * Add a batch of trading values for a symbol.
     */
    @POST
    @Path("/add_batch/")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response addBatch(BatchRequest request) {
        if (request.getSymbol() == null || request.getValues() == null || request.getValues().isEmpty()) {
            throw new BadRequestException("symbol and values must be provided");
        }
        manager.getAndPushCalculations(request.getSymbol(), request.getValues());
        return Response.ok("Batch added for " + request.symbol + ", size=" + request.getValues().size()).build();
    }

    /**
     * GET /stats/?symbol=AAPL&k=3
     * Get statistics for the last 10^k values.
     */
    @GET
    @Path("/stats/")
    @Produces(MediaType.APPLICATION_JSON)
    public Stats getStats(@QueryParam("symbol") String symbol,
                                  @QueryParam("k") int k) {
        if (symbol == null || k < 1 || k > 8) {
            throw new BadRequestException("Invalid symbol or k (1-8)");
        }
        SymbolAggregator aggregator = manager.getOrCreate(symbol);
       return aggregator.getStats(k);
    }
}
