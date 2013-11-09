package com._42six.amino.data.utilities;

import com._42six.amino.api.job.AminoJob;
import com._42six.amino.common.Bucket;
import com._42six.amino.common.service.datacache.BucketCache;
import com._42six.amino.common.service.datacache.BucketNameCache;
import com._42six.amino.common.service.datacache.DataSourceCache;
import com._42six.amino.common.service.datacache.VisibilityCache;
import com._42six.amino.common.util.PathUtils;
import com._42six.amino.data.DataLoader;
import com.google.common.collect.Sets;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;

import java.io.IOException;
import java.util.SortedSet;
import java.util.TreeSet;

public class CacheBuilder {
	
	public static void buildCaches(DataLoader dataLoader, AminoJob job, String rootOutputPath, Configuration conf) throws IOException {
		PathUtils.setCachePath(conf, PathUtils.getJobCachePath(rootOutputPath));
		
		final BucketCache bucketCache = new BucketCache();
		final BucketNameCache bucketNameCache = new BucketNameCache();
        final DataSourceCache dataSourceCache = new DataSourceCache();
        final VisibilityCache visibilityCache = new VisibilityCache();

		final String datasourceName = dataLoader.getDataSourceName();
		final String visibility = dataLoader.getVisibility();
		final String hrVisibility = dataLoader.getHumanReadableVisibility();
		
		Integer domainId = null;
		String domainName = null;
		String domainDescription = null;
        final SortedSet<Text> bucketNames = new TreeSet<Text>();

		// Get domain info for this dataset
		if (job != null) {
			domainId = job.getAminoDomainID();
			domainName = job.getAminoDomainName();
			domainDescription = job.getAminoDomainDescription();
		}

		for(Text dataKey : dataLoader.getBuckets()) {
            bucketNames.add(new Text(dataKey));
			Text displayName = dataLoader.getBucketDisplayNames().get(dataKey);
			
			Bucket bucket = new Bucket(datasourceName, dataKey.toString(), "", displayName == null ? null : displayName.toString(), visibility, hrVisibility);
			bucket.overrideBucketDataSourceWithDomain(domainId, domainName, domainDescription);
			bucketCache.addBucket(bucket);
		}
		
		// write to disk and add to distributed cache
		bucketCache.writeToDisk(conf, true);

        // Write the cached data to the file system and then when
        // we use the BucketMapper, we can write out the names as an index (saving space) and providing ordering so that
        // we don't have to use a Key, and instead can use a ByBucketKey which can be sorted

        bucketNameCache.setSortedValues(bucketNames);
        dataSourceCache.setValues(Sets.newHashSet((domainId != null) ? new Text(domainId.toString()) : new Text(datasourceName)));
        visibilityCache.setValues(Sets.newHashSet(new Text(visibility)));
        bucketNameCache.persist(conf, true);
        dataSourceCache.persist(conf, true);
        visibilityCache.persist(conf, true);
	}

}
