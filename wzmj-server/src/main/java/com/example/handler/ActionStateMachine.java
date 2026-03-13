package com.example.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.example.model.PendingAction;
import msg.GameMessage.CardInfo;

/**
 * 多人动作拦截状态机
 */
public class ActionStateMachine {
    // 动作缓冲区：记录当前回合收到的所有有效拦截动作 (Key: 座位号)
    private Map<Integer, PendingAction> actionBuffer = new HashMap<>();
    
    // 状态机是否正在等待拦截
    private boolean isIntercepting = false; 
    
    // 核心开关：是否允许“吃”逻辑 (可随时改为 false 关闭)
    private boolean isChiEnabled = true;

    // 触发结算所需的响应总数 (通常是总人数 - 1，即除了出牌者之外的所有人)
    private int requiredResponses = 0;

    // 记录出牌人的座位和总人数，用于计算冲突时的座位距离
    private int currentDiscarderSeat = 0;
    private int totalRoomPlayers = 4;

    /**
     * 开启拦截收集窗口
     * 增加出牌人座位参数
     */
    public void startInterceptWindow(int totalPlayers, int discarderSeat) {
        this.actionBuffer.clear();
        this.isIntercepting = true;
        
        // 极度严谨的数值兜底
        this.totalRoomPlayers = Math.max(2, totalPlayers);
        this.currentDiscarderSeat = Math.max(0, discarderSeat);
        
        this.requiredResponses = this.totalRoomPlayers - 1; 
        
        System.out.println("【状态机】玩家 " + this.currentDiscarderSeat + " 出牌，开启拦截窗口，等待 " + this.requiredResponses + " 名玩家响应...");
    }

    /**
     * 接收玩家发来的动作指令
     */
    public void receiveAction(int seatIndex, int actionCode, int totalFan, List<String> fanNames, List<CardInfo> extraCards) {
        // 1. 如果窗口已关闭，丢弃该消息
        if (!this.isIntercepting) return;

        // 2. 数值安全清洗
        int safeActionCode = Math.max(0, actionCode);
        
        // 3. 动态“吃”逻辑降级：如果未开启吃功能，把吃(5)强制降级为过(6)
        if (!this.isChiEnabled && safeActionCode == 5) {
            safeActionCode = 6; 
        }

        // 4. 生成安全的动作对象并存入缓冲区
        PendingAction action = new PendingAction(seatIndex, safeActionCode, totalFan, fanNames, extraCards);
        this.actionBuffer.put(action.seatIndex, action);
        
        System.out.println("【状态机】收到玩家 " + action.seatIndex + " 的动作: " + action.actionCode + " | 当前已收集: " + this.actionBuffer.size() + "/" + this.requiredResponses);

        // 5. 检查是否所有人（除出牌者外）都已经做出了选择
        if (this.actionBuffer.size() >= this.requiredResponses) {
            System.out.println("【状态机】收集完毕！开始执行优先级比较...");
            resolveHighestPriorityAction();
        }
    }

    /**
     * 关闭窗口，清理数据
     */
    public void resetMachine() {
        this.isIntercepting = false;
        this.actionBuffer.clear();
    }

    /**
     * 内部结算：选出优先级最高的操作执行
     * @return 返回最终胜出的动作（如果所有人都点了“过”，则返回 null）
     */
    public PendingAction resolveHighestPriorityAction() {
        // 1. 关闭收集窗口，防止重复触发
        this.isIntercepting = false; 
        
        PendingAction bestAction = null;
        int maxPriority = 0;
        int minDistance = 999; // 记录离出牌人的顺位距离，越小越优先

        // 2. 遍历缓冲区里的所有动作
        for (PendingAction action : this.actionBuffer.values()) {
            
            // 如果玩家点了“过”(priority == 0)，直接跳过不参与竞争
            if (action.priority <= 0) {
                continue;
            }

            // 距离计算公式：(当前动作发起者座位 - 出牌者座位 + 总人数) % 总人数
            // 例如：0号出牌，1号的距离是 1，2号的距离是 2，3号的距离是 3
            int distance = (action.seatIndex - this.currentDiscarderSeat + this.totalRoomPlayers) % this.totalRoomPlayers;
            
            // 安全防范距离计算异常
            int safeDistance = Math.max(1, distance);

            // 3. 优先级比较逻辑
            if (action.priority > maxPriority) {
                // 场景 A：绝对优先级压制（比如胡压倒碰）
                maxPriority = action.priority;
                bestAction = action;
                minDistance = safeDistance;
            } 
            else if (action.priority == maxPriority && maxPriority > 0) {
                // 场景 B：同级冲突（比如两人同时抢胡）
                // 此时比较谁离出牌人更近（截胡原则）
                if (safeDistance < minDistance) {
                    bestAction = action;
                    minDistance = safeDistance;
                }
            }
        }

        // 4. 打印最终筛选结果
        if (bestAction != null) {
            System.out.println("【状态机】筛选完成！胜出者: 座位 " + bestAction.seatIndex + "，动作: " + bestAction.actionCode);
        } else {
            System.out.println("【状态机】筛选完成！所有人都选择了“过”或没有有效动作。");
        }

        return bestAction;
    }

    // --- 状态访问器 ---
    public boolean isIntercepting() { return isIntercepting; }
}