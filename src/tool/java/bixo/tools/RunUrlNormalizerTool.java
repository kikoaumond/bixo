package bixo.tools;

import java.io.File;
import java.util.List;

import org.apache.commons.io.FileUtils;

import bixo.urls.BaseUrlNormalizer;
import bixo.urls.SimpleUrlNormalizer;

public class RunUrlNormalizerTool {

    /**
     * @param args
     */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        String curUrl = null;
        
        try {
            List<String> lines = FileUtils.readLines(new File(args[0]));

            BaseUrlNormalizer urlNormalizer = new SimpleUrlNormalizer();
            for (String url : lines) {
                curUrl = url;
                String normalized = urlNormalizer.normalize(curUrl);
                if (!normalized.equalsIgnoreCase(curUrl)) {
                    System.out.println(curUrl + " ==> " + normalized);
                }
            }
        } catch (Throwable t) {
            System.err.println("Exception while processing URLs: " + t.getMessage());
            System.err.println("Current url: " + curUrl);
            t.printStackTrace(System.err);
            System.exit(-1);
        }
    }

}
