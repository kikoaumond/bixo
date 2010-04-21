package bixo.operations;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

import org.apache.log4j.Logger;

import bixo.cascading.BixoFlowProcess;
import bixo.cascading.LoggingFlowReporter;
import bixo.cascading.NullContext;
import bixo.datum.BaseDatum;
import bixo.datum.FetchedDatum;
import bixo.datum.PreFetchedDatum;
import bixo.datum.ScoredUrlDatum;
import bixo.datum.UrlStatus;
import bixo.fetcher.FetchTask;
import bixo.fetcher.IFetchMgr;
import bixo.fetcher.http.IHttpFetcher;
import bixo.hadoop.FetchCounters;
import bixo.utils.DiskQueue;
import bixo.utils.ThreadedExecutor;
import cascading.flow.FlowProcess;
import cascading.flow.hadoop.HadoopFlowProcess;
import cascading.operation.BaseOperation;
import cascading.operation.Buffer;
import cascading.operation.BufferCall;
import cascading.operation.OperationCall;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;
import cascading.tuple.TupleEntryCollector;

@SuppressWarnings( { "serial", "unchecked" })
public class FetchBuffer extends BaseOperation<NullContext> implements Buffer<NullContext>, IFetchMgr {
    private static Logger LOGGER = Logger.getLogger(FetchBuffer.class);

    private enum StatusCheck {
        EFFICIENT,          // Check, and skip batch of URLs if blocked by domain still active or pending
        POLITE,             // Check, and queue up batch of URLs if not ready.
        RUDE                // Don't check, just go ahead and process.
    }

    private class QueuedValues {
        private static final int MAX_ELEMENTS_IN_MEMORY = 1000;
        
        private DiskQueue<PreFetchedDatum> _queue;
        private Iterator<TupleEntry> _values;
        
        public QueuedValues(Iterator<TupleEntry> values) {
            _values = values;
            _queue = new DiskQueue<PreFetchedDatum>(MAX_ELEMENTS_IN_MEMORY);
        }
        
        public boolean isEmpty() {
            return _queue.isEmpty() && !_values.hasNext();
        }
        
        public PreFetchedDatum nextOrNull(StatusCheck mode) {
            
            // Loop until we have something to return, or there's nothing that we can return.
            while (true) {
                // First see if we've got something in the queue, and if so, then check if it's ready
                // to be processed.
                PreFetchedDatum datum = _queue.peek();
                if (datum != null) {
                    String ref = datum.getGroupingRef();
                    if (_activeRefs.get(ref) == null) {
                        Long nextFetchTime = _pendingRefs.get(ref);
                        if ((nextFetchTime == null) || (nextFetchTime <= System.currentTimeMillis())) {
                            return _queue.remove();
                        }
                    }
                }

                // We have a datum from the queue, but it's not ready to be returned.
                if (datum != null) {
                    switch (mode) {
                        case POLITE:
                            trace("Ignoring top queue item %s (domain still active or pending)", datum.getGroupingRef());
                            break;

                        case RUDE:
                            _queue.remove();
                            return datum;
                            
                        // In efficient fetching, we punt on items that aren't ready.
                        case EFFICIENT:
                            _queue.remove();
                            List<ScoredUrlDatum> urls = datum.getUrls();
                            trace("Skipping %d urls from %s", urls.size(), datum.getGroupingRef());
                            skipUrls(urls, UrlStatus.SKIPPED_TIME_LIMIT, null);
                            break;
                    }
                }
                
                // Nothing ready in the queue, let's see about the iterator.
                if (_values.hasNext()) {
                    datum = new PreFetchedDatum(_values.next().getTuple(), _metaDataFields);
                    String ref = datum.getGroupingRef();
                    if (_activeRefs.get(ref) == null) {
                        Long nextFetchTime = _pendingRefs.get(ref);
                        if ((nextFetchTime == null) || (nextFetchTime <= System.currentTimeMillis())) {
                            return datum;
                        }
                    }

                    if (datum != null) {
                        switch (mode) {
                            case POLITE:
                                trace("Queuing next iter item %s (domain still active or pending)", datum.getGroupingRef());
                                _queue.add(datum);
                                break;

                            case RUDE:
                                return datum;
                                
                            // In efficient fetching, we punt on items that aren't ready.
                            case EFFICIENT:
                                List<ScoredUrlDatum> urls = datum.getUrls();
                                trace("Skipping %d urls from %s", urls.size(), datum.getGroupingRef());
                                skipUrls(urls, UrlStatus.SKIPPED_TIME_LIMIT, null);
                                break;
                        }
                    }
                } else {
                    // TODO KKr - have a peek(index) and a remove(index) call for the DiskQueue,
                    // where index < number of elements in memory. That way we don't get stuck on having
                    // a top-most element that's taking a long time, but there are following elements that
                    // would be ready to go.
                    return null;
                }
            }
        }
    }

    private static final Fields FETCH_RESULT_FIELD = new Fields(BaseDatum.fieldName(FetchBuffer.class, "fetch-exception"));

    // How long to wait before a fetch request gets rejected.
    // TODO KKr - calculate this based on the fetcher policy's max URLs/request
    private static final long REQUEST_TIMEOUT = 100 * 1000L;
    
    // How long to wait before doing a hard termination. Wait twice as long
    // as the longest request.
    private static final long TERMINATION_TIMEOUT = REQUEST_TIMEOUT * 2;

    // Time to sleep when we don't have any URLs that can be fetched.
    private static final long NOTHING_TO_FETCH_SLEEP_TIME = 1000;

    private IHttpFetcher _fetcher;
    private long _crawlEndTime;
    private boolean _efficientFetching;
    private final Fields _metaDataFields;

    private transient ThreadedExecutor _executor;
    private transient BixoFlowProcess _flowProcess;
    private transient TupleEntryCollector _collector;

    private transient Object _refLock;
    private transient ConcurrentHashMap<String, Long> _activeRefs;
    private transient ConcurrentHashMap<String, Long> _pendingRefs;
    
    public FetchBuffer(IHttpFetcher fetcher, long crawlEndTime, Fields metaDataFields) {
        // We're going to output a tuple that contains a FetchedDatum, plus meta-data,
        // plus a result that could be a string, a status, or an exception
        super(FetchedDatum.FIELDS.append(metaDataFields).append(FETCH_RESULT_FIELD));

        _fetcher = fetcher;
        _crawlEndTime = crawlEndTime;
        _efficientFetching = _fetcher.getFetcherPolicy().isSkipBlockedUrls();
        _metaDataFields = metaDataFields;
    }

    @Override
    public boolean isSafe() {
        // We definitely DO NOT want to be called multiple times for the same
        // scored datum, so let Cascading 1.1 know that the output from us should
        // be stashed in tempHfs if need be.
        return false;
    }

    @Override
    public void prepare(FlowProcess flowProcess, OperationCall operationCall) {
        super.prepare(flowProcess, operationCall);

        _flowProcess = new BixoFlowProcess((HadoopFlowProcess) flowProcess);
        _flowProcess.addReporter(new LoggingFlowReporter());

        _executor = new ThreadedExecutor(_fetcher.getMaxThreads(), REQUEST_TIMEOUT);

        _refLock = new Object();
        _pendingRefs = new ConcurrentHashMap<String, Long>();
        _activeRefs = new ConcurrentHashMap<String, Long>();
    }

    @Override
    public void operate(FlowProcess process, BufferCall<NullContext> buffCall) {
        QueuedValues values = new QueuedValues(buffCall.getArgumentsIterator());

        _collector = buffCall.getOutputCollector();

        // Each value is a PreFetchedDatum that contains a set of URLs to fetch in one request from
        // a single server, plus other values needed to set state properly.
        while (!Thread.interrupted() && (System.currentTimeMillis() < _crawlEndTime) && !values.isEmpty()) {
            PreFetchedDatum datum = values.nextOrNull(_efficientFetching ? StatusCheck.EFFICIENT : StatusCheck.POLITE);
            
            try {
                if (datum == null) {
                    trace("Nothing ready to fetch, sleeping...");
                    process.keepAlive();
                    Thread.sleep(NOTHING_TO_FETCH_SLEEP_TIME);
                } else {
                    List<ScoredUrlDatum> urls = datum.getUrls();
                    String ref = datum.getGroupingRef();
                    trace("Processing %d URLs for %s", urls.size(), ref);

                    Runnable doFetch = new FetchTask(this, _fetcher, urls, ref);
                    if (datum.isLastList()) {
                        makeActive(ref, 0L);
                        trace("Executing fetch of %d URLs from %s (last batch)", urls.size(), ref);
                    } else {
                        Long nextFetchTime = System.currentTimeMillis() + datum.getFetchDelay();
                        makeActive(ref, nextFetchTime);
                        trace("Executing fetch of %d URLs from %s (next fetch time %d)", urls.size(), ref, nextFetchTime);
                    }

                    long startTime = System.currentTimeMillis();

                    try {
                        _executor.execute(doFetch);
                    } catch (RejectedExecutionException e) {
                        // should never happen.
                        LOGGER.error("Fetch pool rejected our fetch list for " + ref);

                        finished(ref);
                        _flowProcess.increment(FetchCounters.URLS_SKIPPED, urls.size());
                        skipUrls(urls, UrlStatus.SKIPPED_DEFERRED, String.format("Execution rejection skipped %d URLs", urls.size()));
                    }

                    // Adjust for how long it took to get the request queued.
                    adjustActive(ref, System.currentTimeMillis() - startTime);
                }
            } catch (InterruptedException e) {
                LOGGER.warn("FetchBuffer interrupted!");
                Thread.currentThread().interrupt();
            }
        }
        
        // Skip all URLs that we've got left.
        if (!values.isEmpty()) {
            trace("Found unprocessed URLs");
            
            UrlStatus status = Thread.interrupted() ? UrlStatus.SKIPPED_INTERRUPTED : UrlStatus.SKIPPED_TIME_LIMIT;
            
            while (!values.isEmpty()) {
                PreFetchedDatum datum = values.nextOrNull(StatusCheck.RUDE);
                List<ScoredUrlDatum> urls = datum.getUrls();
                trace("Skipping %d urls from %s", urls.size(), datum.getGroupingRef());
                skipUrls(datum.getUrls(), status, null);
            }
        }
    }

    @Override
    public void cleanup(FlowProcess process, OperationCall operationCall) {
        try {
            if (!_executor.terminate(TERMINATION_TIMEOUT)) {
                LOGGER.warn("Had to do a hard termination of general fetching");
                // TODO KKr - I think we lose entries in this case.
            }
        } catch (InterruptedException e) {
            // FUTURE What's the right thing to do here? E.g. do I need to worry about
            // losing URLs still to be processed?
            LOGGER.warn("Interrupted while waiting for termination");
        }

        _flowProcess.dumpCounters();
    }

    @Override
    public void finished(String ref) {
        synchronized (_refLock) {
            Long nextFetchTime = _activeRefs.remove(ref);
            if (nextFetchTime == null) {
                throw new RuntimeException("finished called on non-active ref: " + ref);
            }
            
            // If there's going to be more to fetch, put it back in the pending pool.
            if (nextFetchTime != 0) {
                trace("Finished batch fetch for %s, with next batch at %d", ref, nextFetchTime);
                _pendingRefs.put(ref, nextFetchTime);
            } else {
                trace("Finished last batch fetch for %s", ref);
            }
        }
    }

    @Override
    public TupleEntryCollector getCollector() {
        return _collector;
    }

    @Override
    public BixoFlowProcess getProcess() {
        return _flowProcess;
    }
    
    private void skipUrls(List<ScoredUrlDatum> urls, UrlStatus status, String traceMsg) {
        for (ScoredUrlDatum datum : urls) {
            FetchedDatum result = new FetchedDatum(datum);
            Tuple tuple = result.toTuple();
            tuple.add(status.toString());
            _collector.add(tuple);
        }

        _flowProcess.increment(FetchCounters.URLS_SKIPPED, urls.size());

        if ((traceMsg != null) && LOGGER.isTraceEnabled()) {
            LOGGER.trace(String.format(traceMsg, urls.size()));
        }
    }
    
    /**
     * Make <ref> active, removing from pending if necessary.
     * 
     * @param ref
     * @param nextFetchTime
     */
    private void makeActive(String ref, Long nextFetchTime) {
        synchronized (_refLock) {
            trace("Making %s active", ref);
            _pendingRefs.remove(ref);
            _activeRefs.put(ref, nextFetchTime);
        }
    }

    private void adjustActive(String ref, long deltaTime) {
        synchronized (_refLock) {
            Long nextFetchTime = _activeRefs.get(ref);
            if ((nextFetchTime != null) && (nextFetchTime != 0) && (deltaTime != 0)) {
                _activeRefs.put(ref, nextFetchTime + deltaTime);
            }
        }
    }

    private void trace(String template, Object... params) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(String.format(template, params));
        }
    }
    

}