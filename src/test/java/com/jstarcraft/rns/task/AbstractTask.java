package com.jstarcraft.rns.task;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.text.DecimalFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.csv.CSVFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jstarcraft.ai.data.DataInstance;
import com.jstarcraft.ai.data.DataModule;
import com.jstarcraft.ai.data.DataSpace;
import com.jstarcraft.ai.data.converter.ArffConverter;
import com.jstarcraft.ai.data.converter.CsvConverter;
import com.jstarcraft.ai.data.converter.DataConverter;
import com.jstarcraft.ai.data.module.ReferenceModule;
import com.jstarcraft.ai.data.processor.DataSplitter;
import com.jstarcraft.ai.environment.EnvironmentContext;
import com.jstarcraft.ai.environment.EnvironmentFactory;
import com.jstarcraft.ai.evaluate.Evaluator;
import com.jstarcraft.ai.math.structure.matrix.HashMatrix;
import com.jstarcraft.ai.math.structure.matrix.SparseMatrix;
import com.jstarcraft.core.common.conversion.json.JsonUtility;
import com.jstarcraft.core.common.option.Option;
import com.jstarcraft.core.common.reflection.ReflectionUtility;
import com.jstarcraft.core.common.reflection.TypeUtility;
import com.jstarcraft.core.utility.Integer2FloatKeyValue;
import com.jstarcraft.core.utility.KeyValue;
import com.jstarcraft.core.utility.RandomUtility;
import com.jstarcraft.core.utility.StringUtility;
import com.jstarcraft.rns.data.processor.QualityFeatureDataSplitter;
import com.jstarcraft.rns.data.separator.DataSeparator;
import com.jstarcraft.rns.data.separator.GivenDataSeparator;
import com.jstarcraft.rns.data.separator.GivenNumberSeparator;
import com.jstarcraft.rns.data.separator.KFoldCrossValidationSeparator;
import com.jstarcraft.rns.data.separator.LeaveOneCrossValidationSeparator;
import com.jstarcraft.rns.data.separator.RandomSeparator;
import com.jstarcraft.rns.data.separator.RatioSeparator;
import com.jstarcraft.rns.model.Model;
import com.jstarcraft.rns.model.exception.ModelException;

import it.unimi.dsi.fastutil.longs.Long2FloatRBTreeMap;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import it.unimi.dsi.fastutil.objects.Object2FloatRBTreeMap;
import it.unimi.dsi.fastutil.objects.Object2FloatSortedMap;

/**
 * 抽象任务
 * 
 * @author Birdy
 *
 * @param <T>
 */
public abstract class AbstractTask<L, R> {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected Option configurator;

    protected String userField, itemField, scoreField;

    protected int userDimension, itemDimension, userSize, itemSize;

    protected ReferenceModule[] trainModules, testModules;

    protected DataModule dataModule, trainMarker, testMarker;

    protected Model model;

    protected AbstractTask(Model model, Option configurator) {
        this.configurator = configurator;
        Long seed = configurator.getLong("recommender.random.seed");
        if (seed != null) {
            RandomUtility.setSeed(seed);
        }
        this.model = model;
    }

    protected AbstractTask(Class<? extends Model> clazz, Option configurator) {
        this.configurator = configurator;
        Long seed = configurator.getLong("recommender.random.seed");
        if (seed != null) {
            RandomUtility.setSeed(seed);
        }
        this.model = (Model) ReflectionUtility.getInstance(clazz);
    }

    protected abstract Collection<Evaluator> getEvaluators(SparseMatrix featureMatrix);

    protected abstract L check(int userIndex);

    protected abstract R recommend(Model recommender, int userIndex);

    private ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    private Map<Class<? extends Evaluator>, Integer2FloatKeyValue> evaluate(Collection<Evaluator> evaluators, Model recommender) {
        Map<Class<? extends Evaluator>, Integer2FloatKeyValue[]> values = new HashMap<>();
        for (Evaluator evaluator : evaluators) {
            values.put(evaluator.getClass(), new Integer2FloatKeyValue[userSize]);
        }
        // 按照用户切割任务.
        CountDownLatch latch = new CountDownLatch(userSize);
        for (int userIndex = 0; userIndex < userSize; userIndex++) {
            int index = userIndex;
            executor.submit(() -> {
                try {
                    ReferenceModule module = testModules[index];
                    if (module.getSize() == 0) {
                        return;
                    }
                    // 校验集合
                    L checkCollection = check(index);
                    // 推荐列表
                    R recommendList = recommend(recommender, index);
                    // 测量列表
                    for (Evaluator<L, R> evaluator : evaluators) {
                        Integer2FloatKeyValue[] measures = values.get(evaluator.getClass());
                        Integer2FloatKeyValue measure = evaluator.evaluate(checkCollection, recommendList);
                        measures[index] = measure;
                    }
                } catch (Exception exception) {
                    logger.error("任务异常", exception);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (Exception exception) {
            throw new ModelException(exception);
        }

        Map<Class<? extends Evaluator>, Integer2FloatKeyValue> measures = new HashMap<>();
        for (Entry<Class<? extends Evaluator>, Integer2FloatKeyValue[]> term : values.entrySet()) {
            Integer2FloatKeyValue measure = new Integer2FloatKeyValue(0, 0F);
            for (Integer2FloatKeyValue element : term.getValue()) {
                if (element == null) {
                    continue;
                }
                measure.setKey(measure.getKey() + element.getKey());
                measure.setValue(measure.getValue() + element.getValue());
            }
            measures.put(term.getKey(), measure);
        }
        return measures;
    }

    public Object2FloatSortedMap<Class<? extends Evaluator>> execute() throws Exception {
        userField = configurator.getString("data.model.fields.user", "user");
        itemField = configurator.getString("data.model.fields.item", "item");
        scoreField = configurator.getString("data.model.fields.score", "score");

        // TODO 数据属性部分
        // 离散属性
        Type dicreteConfiguration = TypeUtility.parameterize(HashMap.class, String.class, Class.class);
        Map<String, Class<?>> dicreteDifinitions = JsonUtility.string2Object(configurator.getString("data.attributes.dicrete"), dicreteConfiguration);
        // 连续属性
        Type continuousConfiguration = TypeUtility.parameterize(HashMap.class, String.class, Class.class);
        Map<String, Class<?>> continuousDifinitions = JsonUtility.string2Object(configurator.getString("data.attributes.continuous"), continuousConfiguration);

        // 数据空间部分
        DataSpace space = new DataSpace(dicreteDifinitions, continuousDifinitions);

        // TODO 数据模型部分
        ModuleConfigurer[] moduleConfigurers = JsonUtility.string2Object(configurator.getString("data.modules"), ModuleConfigurer[].class);
        for (ModuleConfigurer moduleConfigurer : moduleConfigurers) {
            space.makeDenseModule(moduleConfigurer.getName(), moduleConfigurer.getConfiguration(), 1000000000);
        }

        // TODO 数据转换器部分
        Type convertorConfiguration = TypeUtility.parameterize(LinkedHashMap.class, String.class, TypeUtility.parameterize(KeyValue.class, String.class, HashMap.class));
        ConverterConfigurer[] converterConfigurers = JsonUtility.string2Object(configurator.getString("data.converters"), ConverterConfigurer[].class);
        for (ConverterConfigurer converterConfigurer : converterConfigurers) {
            String name = converterConfigurer.getName();
            String type = converterConfigurer.getType();
            String path = converterConfigurer.getPath();
            DataConverter convertor = null;
            switch (type) {
            case "arff": {
                convertor = ReflectionUtility.getInstance(ArffConverter.class, space.getQualityAttributes(), space.getQuantityAttributes());
                break;
            }
            case "csv": {
                CSVFormat format = CSVFormat.DEFAULT.withDelimiter(configurator.getCharacter("data.separator.delimiter", ' '));
                convertor = ReflectionUtility.getInstance(CsvConverter.class, format, space.getQualityAttributes(), space.getQuantityAttributes());
                break;
            }
            default: {
                throw new ModelException("不支持的转换格式");
            }
            }
            File file = new File(path);
            DataModule module = space.getModule(name);
            try (InputStream stream = new FileInputStream(file)) {
                convertor.convert(module, stream);
            }
        }

        // TODO 数据切割器部分
        SeparatorConfigurer separatorConfigurer = JsonUtility.string2Object(configurator.getString("data.separator"), SeparatorConfigurer.class);
        DataModule module = space.getModule(separatorConfigurer.getName());
        int scoreDimension = module.getQuantityInner(scoreField);
        for (DataInstance instance : module) {
            // 将特征设置为标记
            instance.setQuantityMark(instance.getQuantityFeature(scoreDimension));
        }
        DataSeparator separator;
        switch (separatorConfigurer.getType()) {
        case "kcv": {
            int size = configurator.getInteger("data.separator.kcv.number", 1);
            separator = new KFoldCrossValidationSeparator(module, size);
            break;
        }
        case "loocv": {
            separator = new LeaveOneCrossValidationSeparator(space, module, separatorConfigurer.getMatchField(), separatorConfigurer.getSortField());
            break;
        }
        case "testset": {
            int threshold = configurator.getInteger("data.separator.threshold");
            separator = new GivenDataSeparator(module, threshold);
            break;
        }
        case "givenn": {
            int number = configurator.getInteger("data.separator.given-number.number");
            separator = new GivenNumberSeparator(space, module, separatorConfigurer.getMatchField(), separatorConfigurer.getSortField(), number);
            break;
        }
        case "random": {
            float random = configurator.getFloat("data.separator.random.value", 0.8F);
            separator = new RandomSeparator(space, module, separatorConfigurer.getMatchField(), random);
            break;
        }
        case "ratio": {
            float ratio = configurator.getFloat("data.separator.ratio.value", 0.8F);
            separator = new RatioSeparator(space, module, separatorConfigurer.getMatchField(), separatorConfigurer.getSortField(), ratio);
            break;
        }
        default: {
            throw new ModelException("不支持的划分类型");
        }
        }

        // 评估部分
        Double binarize = configurator.getDouble("data.convert.binarize.threshold");
        Object2FloatSortedMap<Class<? extends Evaluator>> measures = new Object2FloatRBTreeMap<>((left, right) -> {
            return left.getName().compareTo(right.getName());
        });

        EnvironmentContext context = EnvironmentFactory.getContext();
        StringBuffer message = new StringBuffer();
        Future<?> task = context.doTask(() -> {
            try {
                for (int index = 0; index < separator.getSize(); index++) {
                    trainMarker = separator.getTrainReference(index);
                    testMarker = separator.getTestReference(index);
                    dataModule = module;

                    userDimension = module.getQualityInner(userField);
                    itemDimension = module.getQualityInner(itemField);
                    userSize = space.getQualityAttribute(userField).getSize();
                    itemSize = space.getQualityAttribute(itemField).getSize();

                    DataSplitter splitter = new QualityFeatureDataSplitter(userDimension);
                    trainModules = splitter.split(trainMarker, userSize);
                    testModules = splitter.split(testMarker, userSize);

                    HashMatrix dataTable = new HashMatrix(true, userSize, itemSize, new Long2FloatRBTreeMap());
                    for (DataInstance instance : dataModule) {
                        int rowIndex = instance.getQualityFeature(userDimension);
                        int columnIndex = instance.getQualityFeature(itemDimension);
                        // TODO 处理冲突
                        dataTable.setValue(rowIndex, columnIndex, instance.getQuantityMark());
                    }
                    SparseMatrix featureMatrix = SparseMatrix.valueOf(userSize, itemSize, dataTable);
                    message.append(StringUtility.format("| {} |", model.getClass().getSimpleName()));
                    {
                        long current = System.currentTimeMillis();
                        model.prepare(configurator, trainMarker, space);
                        model.practice();
                        message.append(StringUtility.format(" {} |", System.currentTimeMillis() - current));
                    }
                    {
                        long current = System.currentTimeMillis();
                        for (Entry<Class<? extends Evaluator>, Integer2FloatKeyValue> measure : evaluate(getEvaluators(featureMatrix), model).entrySet()) {
                            float value = measure.getValue().getValue() / measure.getValue().getKey();
                            measures.put(measure.getKey(), value);
                        }
                        message.append(StringUtility.format(" {} |", System.currentTimeMillis() - current));
                    }
                }
            } catch (Exception exception) {
                logger.error("任务异常", exception);
            }
        });
        task.get();

        for (Object2FloatMap.Entry<Class<? extends Evaluator>> term : measures.object2FloatEntrySet()) {
            term.setValue(term.getFloatValue() / separator.getSize());
            if (logger.isDebugEnabled()) {
                logger.debug(StringUtility.format("Assert.assertEquals({}F, measures.getFloat({}.class), 0F);", term.getFloatValue(), term.getKey().getSimpleName()));
            }
            message.append(StringUtility.format(" {} |", format.format(term.getFloatValue())));
        }

        if (logger.isInfoEnabled()) {
            logger.info(message.toString());
        }
        return measures;
    }

    private DecimalFormat format = new DecimalFormat("####0.00000");

    public Model getModel() {
        return model;
    }

    public DataModule getDataModule() {
        return dataModule;
    }

}
