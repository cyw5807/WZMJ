package com.example.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.example.model.PendingAction;
import msg.GameMessage.CardInfo;

/**
 * 带有前瞻预测与动态截断功能的多人动作拦截状态机
 */
public class ActionStateMachine {
    // 动作缓冲区：记录当前回合收到的拦截动作
    private Map<Integer, PendingAction> actionBuffer = new HashMap<>();
    
    // 前瞻预测表：记录每个玩家在当前窗口被允许做出的最高优先级动作
    private Map<Integer, Integer> expectedMaxPriorities = new HashMap<>(); 

    private boolean isIntercepting = false; 
    private boolean isChiEnabled = true;

    private int currentDiscarderSeat = 0;
    private int totalRoomPlayers = 4;
    private int requiredResponses = 0;

    // 抢杠胡专属数据
    private boolean isQiangGangMode = false;
    private CardInfo currentTargetCard = null;

    private GameController gameController;

    public ActionStateMachine(GameController gameController) {
        this.gameController = gameController;
    }

    /**
     * 将动作代码转化为绝对优先级权重用于数学比较
     */
    private int getPriorityWeight(int actionCode) {
        if (actionCode == 4) return 40; // 胡 (最高优先级)
        if (actionCode == 3 || actionCode == 2) return 20; // 碰/明杠 (同级优先级，靠距离决胜)
        if (actionCode == 5) return 10; // 吃 (最低优先级)
        return 0; // 过/放弃 (无优先级)
    }

    /**
     * 1. 开启常规出牌拦截窗口
     */
    public void startInterceptWindow(int totalPlayers, int discarderSeat, CardInfo targetCard, Map<Integer, Integer> expectedPriorities) {
        this.actionBuffer.clear();
        this.expectedMaxPriorities.clear();
        
        this.isIntercepting = true;
        this.isQiangGangMode = false;
        // 无论是否抢杠胡，状态机都必须死死记住这张目标牌！
        this.currentTargetCard = targetCard; 
        
        this.totalRoomPlayers = Math.max(2, totalPlayers);
        this.currentDiscarderSeat = Math.max(0, discarderSeat);

        if (expectedPriorities != null && !expectedPriorities.isEmpty()) {
            // ... 保持原有逻辑不变
            this.expectedMaxPriorities.putAll(expectedPriorities);
            this.requiredResponses = 0;
            for (int expectedAction : this.expectedMaxPriorities.values()) {
                if (getPriorityWeight(expectedAction) > 0) {
                    this.requiredResponses++;
                }
            }
        } else {
            // 安全降级：强制硬等所有人
            this.requiredResponses = this.totalRoomPlayers - 1;
            for (int i = 0; i < this.totalRoomPlayers; i++) {
                if (i != this.currentDiscarderSeat) {
                    this.expectedMaxPriorities.put(i, 4); 
                }
            }
        }
        
        System.out.println("【状态机】拦截通道开启！理论预期等待 " + this.requiredResponses + " 名玩家响应...");
    }

    /**
     * 2. 开启抢杠胡专用拦截窗口
     */
    public void startQiangGangIntercept(int discarderSeat, CardInfo targetCard, int totalPlayers, Map<Integer, Integer> expectedPriorities) {
        // 复用常规窗口的初始化逻辑，传入 targetCard
        startInterceptWindow(totalPlayers, discarderSeat, targetCard, expectedPriorities);
        
        // 追加抢杠专属属性
        this.isQiangGangMode = true;
        System.out.println("【状态机】抢杠拦截开启！挂起补杠操作，等待全场判定...");
    }

    /**
     * 接收并验证玩家指令
     */
    public void receiveAction(int seatIndex, int actionCode, int totalFan, List<String> fanNames, List<CardInfo> extraCards) {
        if (!this.isIntercepting) return;

        int safeActionCode = Math.max(0, actionCode);
        if (!this.isChiEnabled && safeActionCode == 5) safeActionCode = 6;

        // 存入缓冲区
        PendingAction action = new PendingAction(seatIndex, safeActionCode, totalFan, fanNames, extraCards);
        this.actionBuffer.put(seatIndex, action);
        
        System.out.println("【状态机】收到座位 " + seatIndex + " 的动作: " + actionCode);

        // 每收到一个动作，立刻触发数学截断推演！
        checkShortCircuitAndResolve();
    }

    /**
     * 3. 核心算法：短路截断与动态结算
     */
    private void checkShortCircuitAndResolve() {
        int currentBestWeight = 0;
        PendingAction currentBestAction = null;
        int currentBestDistance = 999;

        // 【遍历 A】扫描当前缓冲区，找出已知最优解
        for (PendingAction action : this.actionBuffer.values()) {
            int weight = getPriorityWeight(action.actionCode);
            if (weight <= 0) continue;

            // 顺位距离公式：(当前动作玩家 - 出牌玩家 + 总人数) % 总人数
            int distance = (action.seatIndex - this.currentDiscarderSeat + this.totalRoomPlayers) % this.totalRoomPlayers;
            if (distance <= 0) distance += this.totalRoomPlayers; 

            if (weight > currentBestWeight) {
                currentBestWeight = weight;
                currentBestAction = action;
                currentBestDistance = distance;
            } else if (weight == currentBestWeight) {
                if (distance < currentBestDistance) {
                    currentBestAction = action;
                    currentBestDistance = distance;
                }
            }
        }

        // 【遍历 B】扫描还没回复的玩家，找出他们的“理论最大威胁”
        int pendingBestWeight = 0;
        int pendingBestDistance = 999;

        for (Map.Entry<Integer, Integer> entry : this.expectedMaxPriorities.entrySet()) {
            int pendingSeat = entry.getKey();
            
            // 如果这个玩家还没回复
            if (!this.actionBuffer.containsKey(pendingSeat)) {
                int expectedAction = entry.getValue();
                int weight = getPriorityWeight(expectedAction);
                
                if (weight > 0) {
                    int distance = (pendingSeat - this.currentDiscarderSeat + this.totalRoomPlayers) % this.totalRoomPlayers;
                    if (distance <= 0) distance += this.totalRoomPlayers;

                    if (weight > pendingBestWeight) {
                        pendingBestWeight = weight;
                        pendingBestDistance = distance;
                    } else if (weight == pendingBestWeight) {
                        if (distance < pendingBestDistance) {
                            pendingBestDistance = distance;
                        }
                    }
                }
            }
        }

        // 【决断 C】执行短路判定
        boolean shouldResolve = false;

        if (this.actionBuffer.size() >= this.requiredResponses) {
            // 常规结局：所有该回答的人都回答了
            shouldResolve = true;
        } else if (currentBestAction != null) {
            if (currentBestWeight > pendingBestWeight) {
                // 绝对碾压：当前动作（如碰）大于剩下玩家能做的极限（如吃） -> 瞬间截断！
                System.out.println("【状态机】触发绝对截断！最优操作已无可撼动，跳过未操作玩家。");
                shouldResolve = true;
            } else if (currentBestWeight == pendingBestWeight && currentBestDistance < pendingBestDistance) {
                // 距离压制：当前动作（如顺位近的胡）与剩下的极限（顺位远的胡）同级，但距离更近 -> 瞬间截断！
                System.out.println("【状态机】触发距离截断！当前玩家拥有顺位优先权，跳过未操作玩家。");
                shouldResolve = true;
            }
        }

        // 满足任何结算条件，立即关闭机器并返回结果
        if (shouldResolve) {
            executeFinalResolution(currentBestAction);
        }
    }

    /**
     * 彻底关闭窗口并处理结算分发
     */
    private void executeFinalResolution(PendingAction bestAction) {
        this.isIntercepting = false;
        
        if (bestAction != null) {
            System.out.println("【状态机】计算完毕！最终执行者: 座位 " + bestAction.seatIndex + "，动作: " + bestAction.actionCode);
            
            int seatIndex = bestAction.seatIndex;
            int actionCode = bestAction.actionCode;

            // 根据状态机算出的最优动作，回调 GameController 执行实际的数据流转
            if (actionCode == 4) {
                // 执行胡牌结算 (参数传入是否为抢杠胡，以及收集到的番数)
                this.gameController.executeInterceptHu(seatIndex, this.currentTargetCard, this.isQiangGangMode, bestAction.totalFan, bestAction.fanNames);
            } else if (actionCode == 3) {
                // 执行明杠
                this.gameController.executeMingGang(seatIndex, this.currentTargetCard);
            } else if (actionCode == 2) {
                // 执行碰牌
                this.gameController.executePong(seatIndex, this.currentTargetCard);
            } else if (actionCode == 5) {
                // 执行吃牌 (带上玩家选择的两张辅助牌)
                this.gameController.executeChi(seatIndex, this.currentTargetCard, bestAction.extraCards);
            }
            
        } else {
            System.out.println("【状态机】计算完毕！所有人放弃操作，拦截通道放行。");
            
            // 【抢杠胡专属收尾】：无人抢胡，释放被挂起的补杠流程
            if (this.isQiangGangMode) {
                System.out.println("【状态机】抢杠胡无人响应，给原补杠玩家发放岭上牌...");
                // 回调控制器，直接将岭上牌塞给当时触发补杠的玩家
                this.gameController.executeReplacementDraw(this.currentDiscarderSeat);
            } 
            // 常规打牌拦截收尾：无人拦截，流转给下家摸牌
            else {
                System.out.println("【状态机】常规出牌无人拦截，通知下家摸牌...");
                this.gameController.executeNextPlayerDraw();
            }
        }
        
        // 结算完成后，彻底重置并清理状态机内存
        this.resetMachine();
    }

    /**
     * 清理状态
     */
    public void resetMachine() {
        this.isIntercepting = false;
        this.isQiangGangMode = false;
        this.currentTargetCard = null;
        this.actionBuffer.clear();
        this.expectedMaxPriorities.clear();
    }

    // --- 状态访问器 ---
    public boolean isIntercepting() { return isIntercepting; }
    public boolean isQiangGangMode() { return isQiangGangMode; }
    public CardInfo getCurrentTargetCard() { return currentTargetCard; }
}