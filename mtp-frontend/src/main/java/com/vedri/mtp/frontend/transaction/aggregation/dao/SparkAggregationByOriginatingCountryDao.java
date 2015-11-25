package com.vedri.mtp.frontend.transaction.aggregation.dao;

import static com.vedri.mtp.core.transaction.aggregation.TimeAggregation.TimeFields.*;
import static com.vedri.mtp.core.transaction.aggregation.TransactionAggregationByCountry.Fields.amountPointsUnscaled;
import static com.vedri.mtp.core.transaction.aggregation.TransactionAggregationByCountry.Fields.transactionCount;

import java.util.ArrayList;

import org.apache.spark.FutureAction;
import org.apache.spark.rdd.RDD;
import org.apache.spark.streaming.api.java.JavaStreamingContext;

import scala.collection.Seq;
import scala.reflect.ClassTag$;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.pattern.Patterns;

import com.datastax.spark.connector.japi.CassandraStreamingJavaUtil;
import com.google.common.collect.Lists;
import com.vedri.mtp.core.CoreProperties;
import com.vedri.mtp.core.transaction.aggregation.TransactionAggregationByCountry;
import com.vedri.mtp.core.transaction.aggregation.YearToHourTime;
import com.vedri.mtp.core.transaction.aggregation.dao.YearToHourTimeUtil;

public class SparkAggregationByOriginatingCountryDao {

	private final String tableName;
	private final JavaStreamingContext streamingContext;
	private final CoreProperties.Cassandra cassandra;
	private final ActorSystem actorSystem;

	public SparkAggregationByOriginatingCountryDao(final String tableName,
			final JavaStreamingContext streamingContext,
			final CoreProperties.Cassandra cassandra, final ActorSystem actorSystem) {
		this.tableName = tableName;
		this.streamingContext = streamingContext;
		this.cassandra = cassandra;
		this.actorSystem = actorSystem;
	}

	public void load(final String originatingCountry, final YearToHourTime yearToHourTime, final ActorRef requester) {

		final ArrayList<Object> queryArgs = Lists.newArrayList(originatingCountry);
		final String query = YearToHourTimeUtil.composeQuery(yearToHourTime, "originating_country = ?", queryArgs);

		final RDD<TransactionAggregationByCountry> rdd = CassandraStreamingJavaUtil
				.javaFunctions(streamingContext)
				.cassandraTable(cassandra.getKeyspace(), tableName)
				.where(query, queryArgs.toArray())
				.map(row -> new TransactionAggregationByCountry(
						originatingCountry, row.getInt(year.F.underscore()), row.getInt(month.F.underscore()),
						row.getInt(day.F.underscore()), row.getInt(hour.F.underscore()),
						row.getLong(transactionCount.F.underscore()),
						row.getLong(amountPointsUnscaled.F.underscore())))
				.rdd();

		final FutureAction<Seq<TransactionAggregationByCountry>> futureAction = RDD
				.rddToAsyncRDDActions(rdd, ClassTag$.MODULE$.apply(TransactionAggregationByCountry.class))
				.collectAsync();

		Patterns.pipe(futureAction, actorSystem.dispatcher()).to(requester);
	}

}
