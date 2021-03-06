package com._42six.amino.bitmap;

import com._42six.amino.api.framework.FrameworkDriver;
import com._42six.amino.common.AminoConfiguration;
import com._42six.amino.common.DateFeatureMetadata;
import com._42six.amino.common.FeatureFactType;
import com._42six.amino.common.FeatureMetadata;
import com._42six.amino.common.accumulo.IteratorUtils;
import com._42six.amino.common.bigtable.TableConstants;
import com._42six.amino.common.translator.FeatureFactTranslatorImpl;
import com._42six.amino.common.translator.FeatureFactTranslatorInt;
import com.google.common.base.Optional;
import com.google.gson.Gson;
import org.apache.accumulo.core.client.*;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.client.mapreduce.AccumuloInputFormat;
import org.apache.accumulo.core.client.mapreduce.AccumuloOutputFormat;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.accumulo.core.util.Pair;
import org.apache.commons.cli.Option;
import org.apache.commons.math3.stat.descriptive.moment.Mean;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.util.ToolRunner;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

public final class FeatureMetadataJob extends BitmapJob {

	private static final String MAX_NUMBER_OF_NOMINALS = "amino.num.nominals.max";
	private static final int MAX_NUMBER_OF_NOMINALS_DEFAULT = 250;
	private static final String MAX_NUMBER_OF_TOP_NOMINALS = "amino.num.top.nominals.max";
	private static final int MAX_NUMBER_OF_TOP_NOMINALS_DEFAULT = 50;
	private static final String MAX_NUMBER_OF_RATIO_BINS = "amino.num.ratio.bins.max";
	private static final int MAX_NUMBER_OF_RATIO_BINS_DEFAULT = 20;

	/**
	 * Factory for creating the different types of handlers for different types of features
	 */
	private static class HandlerFactory {
		public static FeatureHandler createHandler(FeatureMetadata meta, int maxNominals, int maxTopNominals, Hashtable<String,Distribution> distros) {
			if (meta.type.equals("NOMINAL") || meta.type.equals("ORDINAL")) {
				return new NominalHandler(meta, maxNominals, maxTopNominals);
			} else if (FeatureFactType.numericIntervalTypes.contains(meta.type)) {//meta.type.equals("INTERVAL") || meta.type.equals("RATIO")) {
				return new IntervalHandler(meta, distros);
                        } else if (FeatureFactType.dateIntervalTypes.contains(meta.type)) {
                                return new DateHandler(meta);
			} else {
				return new FeatureHandler(meta);
			}
		}
	}

	private static class FeatureHandler {
		protected FeatureMetadata meta;

		protected FeatureHandler(FeatureMetadata meta) {
			this.meta = meta;
		}

		public void handle(Scanner scanner) {}
		public void summarize() {}
		public Mutation createMutation(ColumnVisibility cv) { return null; }
	}

    private static class DateHandler extends FeatureHandler {
        private FeatureFactTranslatorInt translator = new FeatureFactTranslatorImpl();
        private Hashtable<String, String> min = new Hashtable<>();
        private Hashtable<String, String> max = new Hashtable<>();

        protected DateHandler(FeatureMetadata meta) {
            super(new DateFeatureMetadata(meta));
            DateFeatureMetadata dmeta = (DateFeatureMetadata)this.meta;
            dmeta.minDate = new Hashtable<>();
            dmeta.maxDate = new Hashtable<>();
        }

        @Override
        public void handle(Scanner scanner) {
            for (Entry<Key, Value> entry : scanner) {
                final String columnType = entry.getKey().getColumnQualifier().toString();
                final String date = entry.getKey().getColumnFamily().toString();

                if (columnType.contains("COUNT")) {
                    final long count = Long.parseLong(entry.getValue().toString());
                    final String[] typeParts = columnType.split(":");
                    final String bucket = typeParts[0];

                    // Add the bucket value count
                    meta.addToBucketValueCount(bucket, count);

                    // Get (possibly set) the min/max
                    String minDate = min.get(bucket);
                    String maxDate = max.get(bucket);

                    if (minDate == null) {
                        minDate = date;
                    } else {
                        minDate = (date.compareTo(minDate) < 0)? date : minDate;
                    }
                    min.put(bucket, minDate);

                    if (maxDate == null) {
                        maxDate = date;
                    } else {
                        maxDate = (date.compareTo(maxDate) > 0)? date : maxDate;
                    }
                    max.put(bucket, maxDate);


                }
            }
        }

        @Override
        public void summarize() {
            DateFeatureMetadata dmeta = (DateFeatureMetadata)this.meta;
            for (Entry<String, String> entry : min.entrySet()) {
                dmeta.minDate.put(entry.getKey(), translator.toDate(entry.getValue()));
            }
            for (Entry<String, String> entry : max.entrySet()) {
                dmeta.maxDate.put(entry.getKey(), translator.toDate(entry.getValue()));
            }
        }

        @Override
        public Mutation createMutation(ColumnVisibility cv) {
            final Gson gson = new Gson();
            DateFeatureMetadata dmeta = (DateFeatureMetadata)this.meta;
            final Mutation mutation = new Mutation(TableConstants.FEATURE_PREFIX + meta.id);

            mutation.put("bucketValueCount", "", cv, gson.toJson(meta.bucketValueCount));
            mutation.put("max", "", cv, gson.toJson(dmeta.maxDate));
            mutation.put("min", "", cv, gson.toJson(dmeta.minDate));

            return mutation;
        }

    }

	private static class NominalHandler extends FeatureHandler {
		private boolean overflow = false;
		private int maxNominals;
		private Hashtable<String,TreeSet<FeatureFactCount>> counts = new Hashtable<>();
		private Hashtable<String,FeatureFactCount> otherCounts = new Hashtable<>();
		private int maxTopNominals = 50;

		protected NominalHandler(FeatureMetadata meta, int maxNominals, int maxTopNominals) {
			super(meta);
			this.maxNominals = maxNominals;
			meta.allowedValues = new TreeSet<>();
			this.maxTopNominals = maxTopNominals;
		}

		@Override
		public void handle(Scanner scanner) {

			for (Entry<Key, Value> entry : scanner) {
				final String type = entry.getKey().getColumnQualifier().toString();
				final String nominal = entry.getKey().getColumnFamily().toString();

				if (type.contains("COUNT"))
				{
					final long count = Long.parseLong(entry.getValue().toString());
					final String[] typeParts = type.split(":");
					final String bucket = typeParts[0];
					meta.incrementFeatureFactCount(bucket);
					meta.addToBucketValueCount(bucket, count);

					final FeatureFactCount ffc = new FeatureFactCount(nominal, count);
					if (counts.containsKey(bucket))
					{
						counts.get(bucket).add(ffc);
						if (counts.get(bucket).size() > maxTopNominals)
						{
							FeatureFactCount last = counts.get(bucket).last();
							counts.get(bucket).remove(last);
							if (otherCounts.containsKey(bucket))
							{
								otherCounts.get(bucket).increment(last.count);
							}
							else
							{
								FeatureFactCount ffcOther = new FeatureFactCount("Other", last.count);
								otherCounts.put(bucket, ffcOther);
							}
						}
					}
					else
					{
						TreeSet<FeatureFactCount> ts = new TreeSet<>();
						ts.add(ffc);
						counts.put(bucket, ts);
					}
				}

				// Add allowed values until we hit the max
				if (!overflow && !meta.allowedValues.contains(nominal)) {
					meta.allowedValues.add(nominal);

					if (meta.allowedValues.size() > maxNominals) {
						meta.allowedValues.clear();
						overflow = true;
					}
				}
			}
		}

		@Override
		public void summarize()
		{
			Enumeration<String> keys = otherCounts.keys();
			while(keys.hasMoreElements())
			{
				String key = keys.nextElement();
				FeatureFactCount ffc = otherCounts.get(key);
				counts.get(key).add(ffc);
			}

			Hashtable<String,ArrayList<String>> topN = new Hashtable<>();
			keys = counts.keys();
			while(keys.hasMoreElements())
			{
				ArrayList<String> list = new ArrayList<>();
				String key = keys.nextElement();
				TreeSet<FeatureFactCount> ffcs = counts.get(key);
				for (FeatureFactCount ffc : ffcs)
				{
					list.add(ffc.toString());
				}
				topN.put(key, list);
			}
			meta.topN = topN;
		}

		@Override
		public Mutation createMutation(ColumnVisibility vis){
			final Gson gson = new Gson();
			final Mutation mutation = new Mutation(TableConstants.FEATURE_PREFIX + meta.id);
			mutation.put("allowedValues", "", vis, gson.toJson(meta.allowedValues));
			mutation.put("bucketValueCount", "", vis, gson.toJson(meta.bucketValueCount));
			mutation.put("featureFactCount", "", vis, gson.toJson(meta.featureFactCount));
			mutation.put("topN", "", vis, gson.toJson(meta.topN));
			return mutation;
		}

		private class FeatureFactCount implements Comparable<FeatureFactCount>
		{
			public String fact;
			public long count;

			public FeatureFactCount(String fact, long count)
			{
				this.fact = fact;
				this.count = count;
			}

			public void increment(long val)
			{
				count += val;
			}

			@Override
			public String toString()
			{
				return fact + ":" + count;
			}

			@Override
			public int hashCode() {
				final int prime = 31;
				int result = 1;
				result = prime * result + getOuterType().hashCode();
				result = prime * result
						+ ((fact == null) ? 0 : fact.hashCode());
				return result;
			}

			@Override
			public boolean equals(Object obj) {
				if (this == obj)
					return true;
				if (obj == null)
					return false;
				if (getClass() != obj.getClass())
					return false;
				FeatureFactCount other = (FeatureFactCount) obj;
				if (!getOuterType().equals(other.getOuterType()))
					return false;
				if (fact == null) {
					if (other.fact != null)
						return false;
				} else if (!fact.equals(other.fact))
					return false;
				return true;
			}

			private NominalHandler getOuterType() {
				return NominalHandler.this;
			}

			@Override
			public int compareTo(FeatureFactCount ffc)
			{
				//reverse sort
				return new Long(Long.MAX_VALUE - this.count).compareTo(Long.MAX_VALUE - ffc.count);
			}
		}
	}

	private static class IntervalHandler extends FeatureHandler {

		private FeatureFactTranslatorInt translator = new FeatureFactTranslatorImpl();
		private Hashtable<String,Mean> means = new Hashtable<>();
		private Hashtable<String,StandardDeviation> deviations = new Hashtable<>();
		//Hashtable<String,ArrayList<Double>> percentiles = new Hashtable<>();
		private Hashtable<String,KMeansSet> ratioBins = new Hashtable<>();

		protected IntervalHandler(FeatureMetadata meta, Hashtable<String,Distribution> distro) {
			super(meta);

//			meta.min = new Double(Double.POSITIVE_INFINITY);
//			meta.max = new Double(Double.NEGATIVE_INFINITY);

			Hashtable<String,Double> mins = new Hashtable<>();
			Hashtable<String,Double> maxs = new Hashtable<>();
			Enumeration<String> keys = distro.keys();
			while (keys.hasMoreElements())
			{
				String key = keys.nextElement();
				Distribution d = distro.get(key);
				mins.put(key, d.min);
				maxs.put(key, d.max);

				KMeansSet set = new KMeansSet(d.pins);
				ratioBins.put(key, set);
			}
			meta.min = mins;
			meta.max = maxs;
		}

		@Override
		public void handle(Scanner scanner) {
			for (Entry<Key, Value> entry : scanner) {
				double x = translator.toRatio(entry.getKey().getColumnFamily().toString());
				final String cq = entry.getKey().getColumnQualifier().toString();
				if (cq.contains("COUNT"))
				{
					final String[] typeParts = cq.split(":");
					final String bucket = typeParts[0];
					final Long count = Long.parseLong(entry.getValue().toString());
					meta.incrementFeatureFactCount(bucket);
					meta.addToBucketValueCount(bucket, count);

					ratioBins.get(bucket).update(count, x);
					if (means.containsKey(bucket))
					{
						means.get(bucket).increment(x);
						deviations.get(bucket).increment(x);
						//percentiles.get(bucket).add(x);
					}
					else
					{
						Mean mean = new Mean();
						mean.increment(x);
						means.put(bucket, mean);

						StandardDeviation sd = new StandardDeviation(false);
						sd.increment(x);
						deviations.put(bucket, sd);

						//ArrayList<Double> p = new ArrayList<Double>();
						//p.add(x);
						//percentiles.put(bucket, p);
					}
				}
				//			if (x < meta.min) {
				//				meta.min = x;
				//			}
				//
				//			if (x > meta.max) {
				//				meta.max = x;
				//			}
			}
		}

		@Override
		public void summarize()
		{
			for(Entry<String, Mean> entry : means.entrySet())
			{
				final String bucket = entry.getKey();
				final Mean mean = entry.getValue();

				if (meta.averages == null) meta.averages = new Hashtable<>();
				if (meta.standardDeviations == null) meta.standardDeviations = new Hashtable<>();
				//if (meta.percentiles == null) meta.percentiles = new Hashtable<>();
				if (meta.ratioBins == null) meta.ratioBins = new Hashtable<>();

				meta.averages.put(bucket, mean.getResult());

				StandardDeviation sd = deviations.get(bucket);
				meta.standardDeviations.put(bucket, sd.getResult());

				KMeansSet set = ratioBins.get(bucket);
				ArrayList<Hashtable<String,Double>> pinList = new ArrayList<>();
				for(KMeansPin pin : set.pins)
				{
					Hashtable<String,Double> pinData = new Hashtable<>();
					//KMeansPin pin = pins.next();
					pinData.put("count", (double)pin.bucketValueCount);
					pinData.put("top", pin.max);
					pinData.put("bottom", pin.min);
					pinList.add(pinData);
				}
				meta.ratioBins.put(bucket, pinList);

//				ArrayList<Double> ps = percentiles.get(bucket);
//				double[] vals = new double[ps.size()];
//				for (int i = 0; i < ps.size(); i++)
//				{
//					vals[i] = ps.get(i);
//				}
//				Hashtable<Integer,Double> pers = new Hashtable<Integer,Double>();
//				Percentile p = new Percentile();
//				p.setData(vals);
//				for (int i = 5; i <= 100; i+=5)
//				{
//					double val = p.evaluate(i);
//					pers.put(i, val);
//				}
//				meta.percentiles.put(bucket, pers);
			}
		}

		@Override
		public Mutation createMutation(ColumnVisibility vis){
			final Gson gson = new Gson();
			final Mutation mutation = new Mutation(TableConstants.FEATURE_PREFIX + meta.id);

			mutation.put("averages", "", vis, gson.toJson(meta.averages));
			mutation.put("bucketValueCount", "", vis, gson.toJson(meta.bucketValueCount));
			mutation.put("featureFactCount", "", vis, gson.toJson(meta.featureFactCount));
			mutation.put("max", "", vis, gson.toJson(meta.max));
			mutation.put("min", "", vis, gson.toJson(meta.min));
			mutation.put("ratioBins", "", vis, gson.toJson(meta.ratioBins));
			mutation.put("standardDeviations", "", vis, gson.toJson(meta.standardDeviations));

			return mutation;
		}

		public static class KMeansSet
		{
			int clusterCount = 20;
			ArrayList<KMeansPin> pins;

			@SuppressWarnings("unused")
			public KMeansSet(int clusterCount)
			{
				this.clusterCount = clusterCount;
				pins = new ArrayList<>(this.clusterCount);
			}

			public KMeansSet(HashSet<Double> pinList)
			{
				this.pins = new ArrayList<>();
				for (double pin : pinList)
				{
					KMeansPin kmp = new KMeansPin();
					kmp.insert(0l, pin);
					this.pins.add(kmp);
				}

				clusterCount = this.pins.size();
			}

			public void update(long bucketValueCount, double val)
			{
				KMeansPin closest = null;
				double smallestDist = Double.MAX_VALUE;
				for (KMeansPin pin : pins)
				{
					double dist = pin.distance(val);
					if (dist < smallestDist)
					{
						smallestDist = dist;
						closest = pin;
					}
					if (smallestDist == 0) break;
				}

				if (closest == null || pins.size() < clusterCount)
				{
					KMeansPin pin = new KMeansPin();
					pin.insert(bucketValueCount, val);
					pins.add(pin);
				}
				else
				{
					closest.insert(bucketValueCount, val);
				}
			}
		}

		public static class KMeansPin
		{
			public long bucketValueCount = 0;
			public long inserted = 0;
			public double total = 0;
			public double avg = 0;
			public double min = Double.MAX_VALUE;
			public double max = Double.MAX_VALUE * -1;

			public void insert(long bucketValueCount, double val)
			{
				this.bucketValueCount += bucketValueCount;
				inserted++;
				total += val;
				avg = total / (double)inserted;
				if (val < min) min = val;
				if (val > max) max = val;
			}

			public double distance(double val)
			{
				return Math.abs(avg - val);
			}

			@Override
			public String toString()
			{
				return "KMeansPin [avg=" + avg + "]";
			}
		}
	}

	private static class FeatureMetadataMapper extends Mapper<Key, Value, Text, Mutation> {

		@Override
		protected void map(Key key, Value value, Context context) throws IOException, InterruptedException {

			final Configuration conf = context.getConfiguration();
			final boolean blastIndex = conf.getBoolean(AminoConfiguration.FIRST_RUN, true);
			final int maxNominals = conf.getInt(MAX_NUMBER_OF_NOMINALS, MAX_NUMBER_OF_NOMINALS_DEFAULT);
			final int maxTopNominals = conf.getInt(MAX_NUMBER_OF_TOP_NOMINALS, MAX_NUMBER_OF_TOP_NOMINALS_DEFAULT);
			final FeatureMetadata metadata = FeatureMetadata.fromJson(value.toString());
			final Text featureIndex = new Text(metadata.id);
			final ColumnVisibility cv = new ColumnVisibility(key.getColumnVisibility());

			// FeatureMetadata meta = FeatureMetadata.fromJson(new Text(value.get()).toString());
			Hashtable<String,Distribution> distros = getDistributions(conf, metadata.type, featureIndex);
			FeatureHandler handler = HandlerFactory.createHandler(metadata, maxNominals, maxTopNominals, distros);

			// Create a scanner for looking up values in the bitmap_bitLookup table
			Scanner bitLookupScanner = createScanner(conf);
			bitLookupScanner.setRange(new Range(featureIndex));
			handler.handle(bitLookupScanner);
			handler.summarize();

			Mutation mutation;
			try {
				mutation = handler.createMutation(cv);
			} catch (IllegalArgumentException ex) {
				return;
			}

			// Persist any new data we might have created
			if(mutation != null){
				// Delete and update the JSON persisted object
				//mutation.putDelete("JSON", "", cv);
				mutation.put("JSON", "", cv, new Value(new Gson().toJson(handler.meta).getBytes()));

				if (blastIndex)
				{
					context.write(new Text(conf.get("amino.metadataTable") + AminoConfiguration.TEMP_SUFFIX), mutation);
				}
				else
				{
					context.write(new Text(conf.get("amino.metadataTable")), mutation);
				}
			}
		}

		private Hashtable<String,Distribution> getDistributions(Configuration conf, String type, Text featureId) throws IOException
		{
			if (!FeatureFactType.numericIntervalTypes.contains(type)) return null;

			Hashtable<String,Distribution> distros = new Hashtable<>();
			FeatureFactTranslatorInt trans = new FeatureFactTranslatorImpl();

			Scanner bitLookupScanner = createScanner(conf);
			bitLookupScanner.setRange(new Range(featureId));
			for (Entry<Key, Value> entry : bitLookupScanner) {
				Key k = entry.getKey();
				Text bucketValue = k.getColumnFamily();
				String cq = k.getColumnQualifier().toString();
				if (cq.contains("COUNT"))
				{
					String[] typeParts = cq.split(":");
					String bucket = typeParts[0];

					//These will be ordered
					Double x = trans.toRatio(bucketValue.toString());
					if (distros.containsKey(bucket))
					{
						distros.get(bucket).increment(x);
					}
					else
					{
						Distribution d = new Distribution(conf);
						d.increment(x);
						distros.put(bucket, d);
					}
				}
			}

			//Now go through again to build the pins
			Hashtable<String,Double> steps = new Hashtable<>();
			Hashtable<String,Long> counters = new Hashtable<>();
			Enumeration<String> keys = distros.keys();
			while (keys.hasMoreElements())
			{
				String bucket = keys.nextElement();
				Distribution d = distros.get(bucket);

				double step = d.total / (double)d.ratioBinCount;
				steps.put(bucket, step);

				if (!counters.containsKey(bucket)) counters.put(bucket, 0l);
			}

			for (Entry<Key, Value> entry : bitLookupScanner) {
				Key k = entry.getKey();
				Text bucketValue = k.getColumnFamily();
				String cq = k.getColumnQualifier().toString();
				if (cq.contains("COUNT"))
				{
					String[] typeParts = cq.split(":");
					String bucket = typeParts[0];

					double step = steps.get(bucket);
					// This took forever to figure out, since it's coming in order, you don't have the average "building up" prior to the first pin
					// Because of this, the first bucket gets screwed up (cut in half), which cascades down to the rest of the bins
					// This allows the first pin to be 1.5 steps away from the next instead of 1 step
					double reach = step;
					if (distros.get(bucket).pins.size() == 0) reach = step + (step / 2.0);
					long counter = counters.get(bucket);
					counters.remove(bucket);
					counter++;
					if (counter >= reach)
					{
						counters.put(bucket, 0l);
						double x = trans.toRatio(bucketValue.toString());
						distros.get(bucket).pins.add(x);
					}
					else
					{
						counters.put(bucket, counter);
					}
				}
			}

			return distros;
		}

		private Scanner createScanner(Configuration conf) throws IOException {
            String instanceName = conf.get(TableConstants.CFG_INSTANCE);
            String zooKeepers = conf.get(TableConstants.CFG_ZOOKEEPERS);
            String user = conf.get(TableConstants.CFG_USER);
            String password = conf.get(TableConstants.CFG_PASSWORD);
			final String indexTable = conf.get(AminoConfiguration.TABLE_INDEX);
			final boolean blastIndex = conf.getBoolean(AminoConfiguration.FIRST_RUN, true);

			Instance inst = new ZooKeeperInstance(instanceName, zooKeepers);
			Connector conn;
			Authorizations auths;
			try {
				conn = inst.getConnector(user, new PasswordToken(password));
				auths = conn.securityOperations().getUserAuthorizations(user);
			} catch (AccumuloException | AccumuloSecurityException ex) {
				throw new IOException(ex);
			}

			Scanner scanner;
			try {
				if (blastIndex)
				{
					scanner = conn.createScanner(indexTable + AminoConfiguration.TEMP_SUFFIX, auths);
				}
				else
				{
					scanner = conn.createScanner(indexTable, auths);
				}
			} catch (TableNotFoundException ex) {
				throw new IOException(ex);
			}

			return scanner;
		}
	}

	private static class Distribution
	{
		public Double min = Double.MAX_VALUE;
		public Double max = Double.MAX_VALUE * -1;
		public Double total = 0.0;
		public int ratioBinCount = 20;
		public HashSet<Double> pins = new HashSet<>();

		public Distribution(Configuration conf)
		{
			this.ratioBinCount = conf.getInt(MAX_NUMBER_OF_RATIO_BINS, MAX_NUMBER_OF_RATIO_BINS_DEFAULT);
		}

		public void increment(double x)
		{
			if (x < min) min = x;
			if (x > max) max = x;
			total++;
			//total += bucketValueCount;
		}
	}

	public void writeNumberOfHashesAndShards(Configuration conf, boolean blastIndex) throws IOException {
        String instanceName = conf.get(TableConstants.CFG_INSTANCE);
        String zooKeepers = conf.get(TableConstants.CFG_ZOOKEEPERS);
        String user = conf.get(TableConstants.CFG_USER);
        String password = conf.get(TableConstants.CFG_PASSWORD);
		final String metadataTable = conf.get(AminoConfiguration.TABLE_METADATA);
		final long maxMemory = conf.getLong(AminoConfiguration.MAX_MEMORY, 1000000L);
		final long maxLatency = conf.getLong(AminoConfiguration.MAX_LATENCY, 1000L);
		final int maxWriteThreads = conf.getInt(AminoConfiguration.MAX_WRITE_THREADS, 10);
//		final String aminoVis = conf.get("amino.visibility");
		// final ColumnVisibility cv = new ColumnVisibility(aminoVis.getBytes());
        final ColumnVisibility cv = new ColumnVisibility();

		final Instance btInstance = new ZooKeeperInstance(instanceName, zooKeepers);
		Connector connector;
		try {
			connector = btInstance.getConnector(user, new PasswordToken(password));
		} catch (AccumuloException | AccumuloSecurityException ex) {
			throw new IOException(ex);
		}

		BatchWriter writer = null;
		try {
            final BatchWriterConfig config = new BatchWriterConfig();
            config.setMaxLatency(maxLatency, TimeUnit.MILLISECONDS);
            config.setMaxMemory(maxMemory);
            config.setMaxWriteThreads(maxWriteThreads);
			if (blastIndex)
			{
                writer = connector.createBatchWriter(metadataTable + AminoConfiguration.TEMP_SUFFIX, config);
			}
			else
			{
                writer = connector.createBatchWriter(metadataTable,config);
			}
			final int numberOfShards = conf.getInt(AminoConfiguration.NUM_SHARDS, 10);
			final int numberOfHashes = conf.getInt(AminoConfiguration.NUM_HASHES, 1);

			final Mutation hashCountMutation = new Mutation("hashcount");
			hashCountMutation.put("", "", cv, Integer.toString(numberOfHashes));
			writer.addMutation(hashCountMutation);

			final Mutation shardCountMutation = new Mutation("shardcount");
			shardCountMutation.put("", "", cv, Integer.toString(numberOfShards));
			writer.addMutation(shardCountMutation);

			writer.flush();
		} catch (TableNotFoundException | MutationsRejectedException e) {
			throw new IOException(e);
		}  finally {
			if(writer != null) {
				try {
					writer.close();
				} catch (MutationsRejectedException e) {
					e.printStackTrace();
				}
			}
		}
	}

	//This is just some clean up if it is an update and we aren't blasting the entire index
	private void cleanupFromUpdate(Configuration conf, Instance inst, String user, byte[] password) throws IOException
	{
		String metadataTable = conf.get(AminoConfiguration.TABLE_METADATA);
		TableOperations tableOps;
		try
		{
			tableOps = inst.getConnector(user, new PasswordToken(password)).tableOperations();
			deleteTables(tableOps, true, metadataTable + AminoConfiguration.TEMP_SUFFIX);
			IteratorUtils.compactTable(tableOps, metadataTable, true);

		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	private void swapTables(Configuration conf, Instance inst, String user, byte[] password) throws IOException
	{
		String metadataTable = conf.get(AminoConfiguration.TABLE_METADATA);
		String lookupTable = conf.get(AminoConfiguration.TABLE_FEATURE_LOOKUP);
		String bucketTable = conf.get(AminoConfiguration.TABLE_BUCKET);
		String indexTable = conf.get(AminoConfiguration.TABLE_INDEX);
		String reverseBucket = bucketTable.replace("amino_", "amino_reverse_");
		String reverseLookup = lookupTable.replace("amino_", "amino_reverse_");

        // Add all tables to a list for iterating over table operations
        List<String> tableNames = new ArrayList<>();
        tableNames.add(metadataTable);
        tableNames.add(lookupTable);
        tableNames.add(bucketTable);
        tableNames.add(indexTable);
        tableNames.add(reverseBucket);
        tableNames.add(reverseLookup);

		TableOperations tableOps;
		try {
			tableOps = inst.getConnector(user, new PasswordToken(password)).tableOperations();

			String metadataTableOld = metadataTable + AminoConfiguration.OLD_SUFFIX;
			String lookupTableOld = lookupTable + AminoConfiguration.OLD_SUFFIX;
			String bucketTableOld = bucketTable + AminoConfiguration.OLD_SUFFIX;
			String indexTableOld = indexTable + AminoConfiguration.OLD_SUFFIX;
			String reverseLookupTableOld = reverseLookup + AminoConfiguration.OLD_SUFFIX;
			String reverseBucketTableOld = reverseBucket + AminoConfiguration.OLD_SUFFIX;

			/* delete old tables if there are any */
			deleteTables(tableOps, false, metadataTableOld, lookupTableOld, bucketTableOld, indexTableOld, reverseLookupTableOld, reverseBucketTableOld);

			/* rename all tables */
            Iterator<String> tblItr = tableNames.iterator();
            while (tblItr.hasNext()) {
                String table = tblItr.next();

                // Backup existing table (if any)
                if (tableOps.exists(table)) {
                    tableOps.rename(table, table+AminoConfiguration.OLD_SUFFIX);
                }

                // Move temp table into place
                try {
                    tableOps.rename(table+AminoConfiguration.TEMP_SUFFIX, table);
                } catch (Exception tex) {
                    // Table operations exception. Restore old table
                    if (tableOps.exists(table+AminoConfiguration.OLD_SUFFIX)) {
                        tableOps.rename(table+AminoConfiguration.OLD_SUFFIX, table);
                    }
                    // Remove this table from the iterator
                    tblItr.remove();
                }
            }

			/* delete old tables */
			deleteTables(tableOps, true, metadataTableOld, lookupTableOld, bucketTableOld, indexTableOld, reverseLookupTableOld, reverseBucketTableOld);

			/* compact tables */
            for (String table: tableNames) {
                IteratorUtils.compactTable(tableOps, table, true);
            }

		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	/**
	 * Bulk delete tables
	 * @param tableOps TableOperations for manipulating the tables
	 * @param fireAndForget true will simply log any errors without throwing an exception
	 * @param tables tables to delete
	 * @throws java.io.IOException
	 */
	private void deleteTables(TableOperations tableOps, boolean fireAndForget, String... tables) throws IOException {
		for (String table : tables) {
			try {
				if (tableOps.exists(table)) {
					tableOps.delete(table);
				}
			} catch (Exception e) {
				if (!fireAndForget) {
					throw new IOException(e);
				}
				else {
					e.printStackTrace();
				}
			}
		}
	}

    public int run(String[] args) throws Exception {

        initializeConfigAndOptions(args, Optional.<HashSet<Option>>absent());
        final Configuration conf = getConf();
        loadConfigValues(conf);
        System.out.println("\n=====================" + conf.get("mapreduce.job.name","FeatureMetadata Job") +"=======================\n");

        final String metadataTable = conf.get(AminoConfiguration.TABLE_METADATA) + AminoConfiguration.TEMP_SUFFIX; // You want to make sure you use the temp here even if blastIndex is false

        final Instance inst = new ZooKeeperInstance(instanceName, zooKeepers);
        final Connector conn = inst.getConnector(user, new PasswordToken(password));
        final Authorizations auths = conn.securityOperations().getUserAuthorizations(user);

        final Job job = new Job(conf, conf.get("mapreduce.job.name","Amino feature metadata job"));
        job.setJarByClass(this.getClass());

        // Configure Mapper
        job.setMapperClass(FeatureMetadataMapper.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Mutation.class);
        job.setInputFormatClass(AccumuloInputFormat.class);

        AccumuloInputFormat.setZooKeeperInstance(job, new ClientConfiguration().withInstance(instanceName).withZkHosts(zooKeepers));
        AccumuloInputFormat.setConnectorInfo(job, user, new PasswordToken(password));
        AccumuloInputFormat.setInputTableName(job, metadataTable);
        AccumuloInputFormat.setScanAuthorizations(job, auths);

        //AccumuloInputFormat.setRegex(job, AccumuloInputFormat.RegexType.ROW, "feature.*");
        AccumuloInputFormat.setRanges(job, Collections.singleton(new Range(new Text("feature"), TableConstants.FEATURE_END)));
        AccumuloInputFormat.fetchColumns(job, Collections.singleton(new Pair<Text, Text>(new Text("JSON"), null)));

        // Configure Reducer
        job.setNumReduceTasks(0);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Mutation.class);
        job.setOutputFormatClass(AccumuloOutputFormat.class);

        AccumuloOutputFormat.setZooKeeperInstance(job, new ClientConfiguration().withInstance(instanceName).withZkHosts(zooKeepers));
        AccumuloOutputFormat.setConnectorInfo(job, user, new PasswordToken(password));
        AccumuloOutputFormat.setCreateTables(job, false);
        AccumuloOutputFormat.setDefaultTableName(job, metadataTable);

        // Run the MapReduce
        final boolean complete = job.waitForCompletion(true);

        // Finishing touches
        writeNumberOfHashesAndShards(conf, blastIndex);

        if (blastIndex)
        {
            swapTables(conf, inst, user, password.getBytes());
        }
        else
        {
            cleanupFromUpdate(conf, inst, user, password.getBytes());
        }

        // TODO - This is hack.  We moved the PID setting in here since the FeatureMetadataJob runs last by itself.
        // There is a problem when parallelizing the jobs because the jobs all try to access the same PID file which is
        // a no no.
        try {
            FrameworkDriver.updateStatus(conf, complete ? FrameworkDriver.JobStatus.COMPLETE : FrameworkDriver.JobStatus.FAILED,
                    new Path(conf.get(AminoConfiguration.BASE_DIR)));
        } catch (IOException e) {
            System.err.println("Could not update the status PID file.");
        }

        return complete ? 0 : 1;
    }


	public static void main(String[] args) throws Exception {
        System.exit(ToolRunner.run(new FeatureMetadataJob(), args));
	}
}
