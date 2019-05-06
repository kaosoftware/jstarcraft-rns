package com.jstarcraft.recommendation.recommender.benchmark.ranking;

import com.jstarcraft.ai.data.DataModule;
import com.jstarcraft.ai.data.DataSpace;
import com.jstarcraft.ai.modem.ModemDefinition;
import com.jstarcraft.recommendation.configurator.Configuration;
import com.jstarcraft.recommendation.recommender.AbstractRecommender;

/**
 * 
 * Most Popular推荐器
 * 
 * <pre>
 * 参考LibRec团队
 * </pre>
 * 
 * @author Birdy
 *
 */
@ModemDefinition(value = { "itemDimension", "populars" })
public class MostPopularRecommender extends AbstractRecommender {

	private int[] populars;

	@Override
	public void prepare(Configuration configuration, DataModule model, DataSpace space) {
		super.prepare(configuration, model, space);
		populars = new int[numberOfItems];
	}

	@Override
	protected void doPractice() {
		for (int itemIndex = 0; itemIndex < numberOfItems; itemIndex++) {
			populars[itemIndex] = trainMatrix.getColumnScope(itemIndex);
		}
	}

	@Override
	public float predict(int[] dicreteFeatures, float[] continuousFeatures) {
		int itemIndex = dicreteFeatures[itemDimension];
		return populars[itemIndex];
	}

}
