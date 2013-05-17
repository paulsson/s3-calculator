package org.duraspace.duracloud.controller.s3;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.servlet.ServletContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;

@Controller
@RequestMapping("/s3/calc")
@ManagedResource(objectName = "S3App:name=CalcMbean")
public class S3CalcController {

    private static final Logger logger = LoggerFactory.getLogger(S3CalcController.class);

    // Prices could be requested from this Amazon URL
    // http://aws.amazon.com/s3/pricing/pricing-storage.json
    
    // number of GBs in a TB
    private int gbPerTB = 1024;
    
    private int[] tiers;
    private Map<Integer, double[]> tierPricing;
    MathContext mc = new MathContext(2);

    @Autowired
    private ServletContext context;
    
    @PostConstruct
    private void init() {
        loadPricing();
    }
    
    private void loadPricing() {
        String webappPath = context.getRealPath(File.separator);
        logger.debug("servlet path: {}", webappPath);
        Properties p = new Properties();
        Map<Integer, double[]> newTierPricing = new HashMap<Integer, double[]>();
        InputStream is = null;
        try {
            //is = Thread.currentThread().getContextClassLoader().getResourceAsStream("pricing.properties");
            File f = new File(webappPath + "WEB-INF/classes/pricing.properties");
            is = new FileInputStream(f);
            p.load(is);
            Set<String> propTiers = p.stringPropertyNames();
            for(String tierStr: propTiers) {
                logger.debug("{}: {}", tierStr, p.getProperty(tierStr));
                int tier = Integer.parseInt(tierStr);
                String[] priceArr = p.getProperty(tierStr).split(",");
                double[] pricing = new double[] {Double.parseDouble(priceArr[0]),
                                                 Double.parseDouble(priceArr[1]),
                                                 Double.parseDouble(priceArr[2])};
                newTierPricing.put(tier, pricing);
            }
            tierPricing = newTierPricing;
            
            tiers = Ints.toArray(tierPricing.keySet());
            Arrays.sort(tiers);
        } catch (IOException e) {
            logger.error("Unable to read 'pricing.properties' file.", e);
        } finally {
            if(is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    logger.error("Problem closing input stream");
                }
            }
        }
    }
    
    @RequestMapping(method = { RequestMethod.GET }, value = { "/months/{months}/tb/{tbCount}" })
    public @ResponseBody Object calcMonthlyTBCost(@PathVariable Integer months,
                                              @PathVariable Integer tbCount) {
        logger.debug("months: {}, TBs: {}", months, tbCount);
        
        double standard = 0;
        double reduced = 0;
        double glacier = 0; 
        int remainder = tbCount;
        
        for(Integer tier: tiers) {
            int tierTbs = remainder;
            if(remainder >= tier) {
                tierTbs = tier;
            }
            remainder -= tierTbs;
            
            double[] prices = tierPricing.get(tier);
            standard += months * prices[0] * gbPerTB * tierTbs;
            reduced += months * prices[1] * gbPerTB * tierTbs;
            glacier += months * prices[2] * gbPerTB * tierTbs;
            
            logger.debug("Calculating for {} TBs in the {} TB tier.", tierTbs, tier);
            logger.debug("{}: {} {} {}", tier, prices[0], prices[1], prices[2]);
            
            if(remainder == 0) {
                break;
            }
        }
        
        return ImmutableMap.of("standard", round(standard, 2, BigDecimal.ROUND_HALF_UP),
                "reduced", round(reduced, 2, BigDecimal.ROUND_HALF_UP),
                "glacier", round(glacier, 2, BigDecimal.ROUND_HALF_UP));
    }
    
    protected double round(double unrounded, int precision, int roundingMode) {
        BigDecimal bd = new BigDecimal(unrounded);
        BigDecimal rounded = bd.setScale(precision, roundingMode);
        return rounded.doubleValue();
    }
    
    @ManagedOperation(description = "Returns the pricing for the specified tier.  Order by 'standard', 'reduced', 'glacier'.")
    public double[] getTierPricing(int tier) {
        return tierPricing.get(tier);
    }
    
    @ManagedOperation(description = "Reload pricing file.")
    public void reloadPricing() {
        loadPricing();
    }
}
