package com.jstarcraft.rns.model.benchmark.rating;

import com.jstarcraft.ai.data.DataInstance;
import com.jstarcraft.ai.data.DataModule;
import com.jstarcraft.ai.data.DataSpace;
import com.jstarcraft.ai.math.structure.vector.SparseVector;
import com.jstarcraft.ai.modem.ModemDefinition;
import com.jstarcraft.core.common.option.Option;
import com.jstarcraft.rns.model.AbstractModel;

/**
 * 
 * User Average推荐器
 * 
 * <pre>
 * 参考LibRec团队
 * </pre>
 * 
 * @author Birdy
 *
 */
@ModemDefinition(value = { "userDimension", "userMeans" })
public class UserAverageModel extends AbstractModel {

    /** 用户平均分数 */
    private float[] userMeans;

    @Override
    public void prepare(Option configuration, DataModule model, DataSpace space) {
        super.prepare(configuration, model, space);
        userMeans = new float[userSize];
    }

    @Override
    protected void doPractice() {
        for (int userIndex = 0; userIndex < userSize; userIndex++) {
            SparseVector userVector = scoreMatrix.getRowVector(userIndex);
            userMeans[userIndex] = userVector.getElementSize() == 0 ? meanScore : userVector.getSum(false) / userVector.getElementSize();
        }
    }

    @Override
    public void predict(DataInstance instance) {
        int userIndex = instance.getQualityFeature(userDimension);
        instance.setQuantityMark(userMeans[userIndex]);
    }

}
