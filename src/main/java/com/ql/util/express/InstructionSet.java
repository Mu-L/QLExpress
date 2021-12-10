package com.ql.util.express;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.ql.util.express.config.QLExpressTimer;
import com.ql.util.express.exception.QLException;
import com.ql.util.express.instruction.FunctionInstructionSet;
import com.ql.util.express.instruction.OperateDataCacheManager;
import com.ql.util.express.instruction.detail.Instruction;
import com.ql.util.express.instruction.detail.InstructionCallSelfDefineFunction;
import com.ql.util.express.instruction.detail.InstructionConstData;
import com.ql.util.express.instruction.detail.InstructionLoadAttr;
import com.ql.util.express.instruction.detail.InstructionNewVirClass;
import com.ql.util.express.instruction.detail.InstructionOperator;
import com.ql.util.express.instruction.opdata.OperateDataLocalVar;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * 表达式执行编译后形成的指令集合
 *
 * @author qhlhl2010@gmail.com
 */
public class InstructionSet {


    private static final transient Log log = LogFactory.getLog(InstructionSet.class);
    public static final AtomicInteger UNIQUE_INDEX = new AtomicInteger(1);
    public static final String TYPE_CLASS = "VClass";
    public static final String TYPE_FUNCTION = "function";
    public static final String TYPE_MARCO = "marco";

    public static final boolean PRINT_INSTRUCTION_ERROR = false;

    private final String type;
    private String name;
    private String globeName;

    /**
     * 指令
     */
    private Instruction[] instructionList = new Instruction[0];
    /**
     * 函数和宏定义
     */
    private final Map<String, FunctionInstructionSet> functionDefine = new HashMap<>();
    //为了增加性能，开始的时候缓存为数组
    private Map<String, Object> cacheFunctionSet = null;
    private final List<ExportItem> exportVar = new ArrayList<>();
    /**
     * 函数参数定义
     */
    private final List<OperateDataLocalVar> parameterList = new ArrayList<>();

    public static int getUniqClassIndex() {
        return UNIQUE_INDEX.getAndIncrement();
    }

    public InstructionSet(String aType) {
        this.type = aType;
    }

    public String[] getOutFunctionNames() {
        Map<String, String> result = new TreeMap<>();
        for (int i = 0; i < instructionList.length; i++) {
            Instruction instruction = instructionList[i];
            if (instruction instanceof InstructionCallSelfDefineFunction) {
                String functionName = ((InstructionCallSelfDefineFunction)instruction).getFunctionName();
                if (!functionDefine.containsKey(functionName)) {
                    result.put(functionName, null);
                }
            }
        }
        return result.keySet().toArray(new String[0]);
    }

    public String[] getVirClasses() {
        Map<String, String> result = new TreeMap<>();
        for (int i = 0; i < instructionList.length; i++) {
            Instruction instruction = instructionList[i];
            if (instruction instanceof InstructionNewVirClass) {
                String functionName = ((InstructionNewVirClass)instruction).getClassName();
                result.put(functionName, null);
            }
        }
        return result.keySet().toArray(new String[0]);
    }

    public String[] getOutAttrNames() throws Exception {
        Map<String, String> result = new TreeMap<>();
        for (Instruction instruction : instructionList) {
            if (instruction instanceof InstructionLoadAttr) {
                if ("null".equals(((InstructionLoadAttr)instruction).getAttrName())) {
                    continue;
                }
                result.put(((InstructionLoadAttr)instruction).getAttrName(), null);
            }
        }

        //剔除本地变量定义和别名定义
        for (int i = 0; i < instructionList.length; i++) {
            Instruction instruction = instructionList[i];
            if (instruction instanceof InstructionOperator) {
                String opName = ((InstructionOperator)instruction)
                    .getOperator().getName();
                if (opName != null) {//addOperator(op)中op.name有可能为空
                    if ("def".equalsIgnoreCase(opName)
                        || "exportDef".equalsIgnoreCase(opName)) {
                        String varLocalName = (String)((InstructionConstData)instructionList[i - 1])
                            .getOperateData().getObject(null);
                        result.remove(varLocalName);
                    } else if ("alias".equalsIgnoreCase(opName)
                        || "exportAlias".equalsIgnoreCase(opName)) {
                        String varLocalName = (String)((InstructionConstData)instructionList[i - 2])
                            .getOperateData().getObject(null);
                        result.remove(varLocalName);
                    }
                }
            }
        }
        return result.keySet().toArray(new String[0]);
    }

    /**
     * 添加指令，为了提高运行期的效率，指令集用数组存储
     *
     * @param item
     * @return
     */
    private void addArrayItem(Instruction item) {
        Instruction[] newArray = new Instruction[this.instructionList.length + 1];
        System.arraycopy(this.instructionList, 0, newArray, 0, this.instructionList.length);
        newArray[this.instructionList.length] = item;
        this.instructionList = newArray;
    }

    /**
     * 插入数据
     *
     * @param aPoint
     * @param item
     */
    private void insertArrayItem(int aPoint, Instruction item) {
        Instruction[] newArray = new Instruction[this.instructionList.length + 1];
        System.arraycopy(this.instructionList, 0, newArray, 0, aPoint);
        System.arraycopy(this.instructionList, aPoint, newArray, aPoint + 1, this.instructionList.length - aPoint);
        newArray[aPoint] = item;
        this.instructionList = newArray;
    }

    /**
     * @param environment
     * @param context
     * @param errorList
     * @param isReturnLastData 是否最后的结果，主要是在执行宏定义的时候需要
     * @param aLog
     * @return
     * @throws Exception
     */
    public CallResult execute(RunEnvironment environment, InstructionSetContext context,
        List<String> errorList, boolean isReturnLastData, Log aLog)
        throws Exception {

        //将函数export到上下文中,这儿就是重入也没有关系，不需要考虑并发
        if (cacheFunctionSet == null) {
            Map<String, Object> tempMap = new HashMap<>();
            for (FunctionInstructionSet s : this.functionDefine.values()) {
                tempMap.put(s.name, s.instructionSet);
            }
            cacheFunctionSet = tempMap;
        }

        context.addSymbol(cacheFunctionSet);

        this.executeInnerOriginalInstruction(environment, errorList, aLog);
        if (!environment.isExit()) {// 是在执行完所有的指令后结束的代码
            if (environment.getDataStackSize() > 0) {
                OperateData tmpObject = environment.pop();
                if (tmpObject == null) {
                    environment.quitExpress(null);
                } else {
                    if (isReturnLastData) {
                        if (tmpObject.getType(context) != null && tmpObject.getType(context).equals(void.class)) {
                            environment.quitExpress(null);
                        } else {
                            environment.quitExpress(tmpObject.getObject(context));
                        }
                    } else {
                        environment.quitExpress(tmpObject);
                    }
                }
            }
        }
        if (environment.getDataStackSize() > 1) {
            throw new QLException("在表达式执行完毕后，堆栈中还存在多个数据");
        }
        return OperateDataCacheManager.fetchCallResult(environment.getReturnValue(), environment.isExit());
    }

    public void executeInnerOriginalInstruction(RunEnvironment environment, List<String> errorList, Log aLog)
        throws Exception {
        Instruction instruction = null;
        try {
            while (environment.programPoint < this.instructionList.length) {
                QLExpressTimer.assertTimeOut();
                instruction = this.instructionList[environment.programPoint];
                // 设置log
                instruction.setLog(aLog);
                instruction.execute(environment, errorList);
            }
        } catch (Exception e) {
            if (PRINT_INSTRUCTION_ERROR) {
                log.error("当前ProgramPoint = " + environment.programPoint);
                log.error("当前指令" + instruction);
                log.error(e);
            }
            throw e;
        }
    }

    public int getInstructionLength() {
        return this.instructionList.length;
    }

    public void addMacroDefine(String macroName, FunctionInstructionSet functionInstructionSet) {
        this.functionDefine.put(macroName, functionInstructionSet);
    }

    public FunctionInstructionSet getMacroDefine(String macroName) {
        return this.functionDefine.get(macroName);
    }

    public FunctionInstructionSet[] getFunctionInstructionSets() {
        return this.functionDefine.values().toArray(new FunctionInstructionSet[0]);
    }

    public void addExportDef(ExportItem e) {
        this.exportVar.add(e);
    }

    public List<ExportItem> getExportDef() {
        List<ExportItem> result = new ArrayList<>();
        result.addAll(this.exportVar);
        return result;
    }

    public OperateDataLocalVar[] getParameters() {
        return this.parameterList.toArray(new OperateDataLocalVar[0]);
    }

    public void addParameter(OperateDataLocalVar localVar) {
        this.parameterList.add(localVar);
    }

    public void addInstruction(Instruction instruction) {
        this.addArrayItem(instruction);
    }

    public void insertInstruction(int point, Instruction instruction) {
        this.insertArrayItem(point, instruction);
    }

    public Instruction getInstruction(int point) {
        return this.instructionList[point];
    }

    public int getCurrentPoint() {
        return this.instructionList.length - 1;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGlobeName() {
        return globeName;
    }

    public void setGlobeName(String globeName) {
        this.globeName = globeName;
    }

    public boolean hasMain() {
        return this.instructionList.length > 0;
    }

    public String getType() {
        return type;
    }

    public void appendSpace(StringBuffer buffer, int level) {
        for (int i = 0; i < level; i++) {
            buffer.append("    ");
        }
    }

    @Override
    public String toString() {
        return "\n" + toString(0);
    }

    public String toString(int level) {
        try {
            StringBuffer buffer = new StringBuffer();
            // 输出宏定义
            for (FunctionInstructionSet set : this.functionDefine.values()) {
                appendSpace(buffer, level);
                buffer.append(set.type + ":" + set.name).append("(");
                for (int i = 0; i < set.instructionSet.parameterList.size(); i++) {
                    OperateDataLocalVar var = set.instructionSet.parameterList.get(i);
                    if (i > 0) {
                        buffer.append(",");
                    }
                    buffer.append(var.getType(null).getName()).append(" ").append(var.getName());
                }
                buffer.append("){\n");
                buffer.append(set.instructionSet.toString(level + 1));
                appendSpace(buffer, level);
                buffer.append("}\n");
            }
            for (int i = 0; i < this.instructionList.length; i++) {
                appendSpace(buffer, level);
                buffer.append(i + 1).append(":").append(this.instructionList[i])
                    .append("\n");
            }
            return buffer.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

	
