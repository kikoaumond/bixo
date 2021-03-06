package bixo.config;

import junit.framework.Assert;

import org.junit.Test;

// TODO Move these tests into DefaultFetchJobPolicyTest, once it supports settings for
// during the fetch portion of the job (versus just the set generation phase).

public class FetcherPolicyTest {

    @Test
    public void testZeroCrawlDelay() {
        FetcherPolicy policy = new FetcherPolicy(FetcherPolicy.NO_MIN_RESPONSE_RATE,
                        FetcherPolicy.DEFAULT_MAX_CONTENT_SIZE, FetcherPolicy.NO_CRAWL_END_TIME, 0,
                        FetcherPolicy.DEFAULT_MAX_REDIRECTS);
        policy.setMaxRequestsPerConnection(100);
        
        try {
//            FetchRequest request = policy.getFetchRequest(System.currentTimeMillis(), 0, 100);
//            Assert.assertEquals(100, request.getNumUrls());
//            Assert.assertTrue(request.getNextRequestTime() <= System.currentTimeMillis());
        } catch (Exception e) {
            Assert.fail("Exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testErrorSettingCrawlDelayInSeconds() {
        try {
            new FetcherPolicy(FetcherPolicy.NO_MIN_RESPONSE_RATE,
                            FetcherPolicy.DEFAULT_MAX_CONTENT_SIZE, FetcherPolicy.NO_CRAWL_END_TIME,
                            30, // Use 30 seconds vs. 30000ms
                            FetcherPolicy.DEFAULT_MAX_REDIRECTS);
            Assert.fail("Should have thrown error with crawl delay of 30");
        } catch (Exception e) {
        }
    }
    
}
