package com.ai.cloud.skywalking.analysis.categorize2chain;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ai.cloud.skywalking.analysis.categorize2chain.filter.SpanNodeProcessChain;
import com.ai.cloud.skywalking.analysis.categorize2chain.filter.SpanNodeProcessFilter;
import com.ai.cloud.skywalking.analysis.categorize2chain.po.ChainInfo;
import com.ai.cloud.skywalking.analysis.categorize2chain.po.ChainNode;
import com.ai.cloud.skywalking.analysis.categorize2chain.util.HBaseUtil;
import com.ai.cloud.skywalking.analysis.categorize2chain.util.SubLevelSpanCostCounter;
import com.ai.cloud.skywalking.analysis.categorize2chain.util.VersionIdentifier;
import com.ai.cloud.skywalking.analysis.config.ConfigInitializer;
import com.ai.cloud.skywalking.protocol.Span;

public class Categorize2ChainMapper extends TableMapper<Text, ChainInfo> {
	private Logger logger = LoggerFactory
			.getLogger(Categorize2ChainMapper.class.getName());

	@Override
	protected void setup(Context context) throws IOException,
			InterruptedException {
		ConfigInitializer.initialize();
	}

	@Override
	protected void map(ImmutableBytesWritable key, Result value, Context context)
			throws IOException, InterruptedException {
		if(!VersionIdentifier.enableAnaylsis(Bytes.toString(key.get()))){
			return;
		}
		
		List<Span> spanList = new ArrayList<Span>();
		ChainInfo chainInfo = null;
		try {
			for (Cell cell : value.rawCells()) {
				Span span = new Span(Bytes.toString(cell.getValueArray(),
						cell.getValueOffset(), cell.getValueLength()));
				spanList.add(span);
			}

			chainInfo = spanToChainInfo(Bytes.toString(key.get()), spanList);
			logger.info("Success convert span to chain info...."
					+ chainInfo.getCID() + " TraceId : " + Bytes.toString(key.get()));
			context.write(
					new Text(chainInfo.getUserId() + ":"
							+ chainInfo.getEntranceNodeToken()), chainInfo);
		} catch (Exception e) {
			logger.error("Failed to mapper call chain[" + key.toString() + "]",
					e);
		}
	}

	public static ChainInfo spanToChainInfo(String key, List<Span> spanList) {
		SubLevelSpanCostCounter costMap = new SubLevelSpanCostCounter();
		ChainInfo chainInfo = new ChainInfo();
		Collections.sort(spanList, new Comparator<Span>() {
			@Override
			public int compare(Span span1, Span span2) {
				String span1TraceLevel = span1.getParentLevel() + "."
						+ span1.getLevelId();
				String span2TraceLevel = span2.getParentLevel() + "."
						+ span2.getLevelId();
				return span1TraceLevel.compareTo(span2TraceLevel);
			}
		});

		Map<String, SpanEntry> spanEntryMap = mergeSpanDataSet(spanList);
		for (Map.Entry<String, SpanEntry> entry : spanEntryMap.entrySet()) {
			ChainNode chainNode = new ChainNode();
			SpanNodeProcessFilter filter = SpanNodeProcessChain
					.getProcessChainByCallType(entry.getValue().getSpanType());
			filter.doFilter(entry.getValue(), chainNode, costMap);
			chainInfo.addNodes(chainNode);
		}

		chainInfo.generateChainToken();
		HBaseUtil.saveCidTidMapping(key, chainInfo);
		return chainInfo;
	}

	private static Map<String, SpanEntry> mergeSpanDataSet(List<Span> spanList) {
		Map<String, SpanEntry> spanEntryMap = new LinkedHashMap<String, SpanEntry>();
		for (int i = spanList.size() - 1; i >= 0; i--) {
			Span span = spanList.get(i);
			SpanEntry spanEntry = spanEntryMap.get(span.getParentLevel() + "."
					+ span.getLevelId());
			if (spanEntry == null) {
				spanEntry = new SpanEntry();
				spanEntryMap.put(
						span.getParentLevel() + "." + span.getLevelId(),
						spanEntry);
			}
			spanEntry.setSpan(span);
		}
		return spanEntryMap;
	}
}
