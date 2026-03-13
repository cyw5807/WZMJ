package com.example.handler;

import java.util.HashSet;
import java.util.Set;

/**
 * 房间状态管理器：负责轮庄、局数倍率及圈数统计
 */
public class RoomManager {
    private int roomSize;                // 动态人数：2, 3 或 4
    private int currentZhuangSeat = 0;   // 当前庄家座位号
    private int zhuangGameCount = 1;     // 当前是该玩家的第几庄 (1, 2, 3)
    private int totalDealerShift = 1;    // 全场第几个庄
    private int roundCount = 1;          // 圈数 (打满 3 圈结束)
    private Set<Integer> hasBeenZhuang = new HashSet<>();

    public RoomManager(int roomSize) {
        this.roomSize = roomSize;
    }

    /**
     * 每局结算后调用，更新下局的庄家状态
     * @param winnerSeat 赢家的座位号，如果是流局，传 -1
     */
    public void updateZhuangAfterRound(int winnerSeat) {
        if (winnerSeat == currentZhuangSeat && zhuangGameCount < 3) {
            // 连庄：庄家位不变，totalDealerShift 不变
            zhuangGameCount++;
        } else {
            // 轮庄：totalDealerShift 增加
            rotateZhuang();
        }
    }

    private void rotateZhuang() {
        // 动态取模，适配不同人数
        currentZhuangSeat = (currentZhuangSeat + 1) % roomSize;
        zhuangGameCount = 1;
        totalDealerShift++; 
        
        hasBeenZhuang.add(currentZhuangSeat);
        
        // 当所有人都当过一轮庄，才算一圈
        if (hasBeenZhuang.size() == roomSize) {
            roundCount++;
            hasBeenZhuang.clear();
        }
    }

    /**
     * 判断整场游戏是否结束
     * 规则：打满 3 圈，意味着总共经历了 roomSize * 3 个庄位更替
     */
    public boolean isGameOver() {
        return totalDealerShift > (this.roomSize * 3);
    }

    // --- Getters ---
    public int getCurrentZhuangSeat() { return currentZhuangSeat; }
    public int getZhuangGameCount() { return zhuangGameCount; }
    public int getRoundCount() { return roundCount; }
    public int getTotalDealerShift() { return totalDealerShift; }
}