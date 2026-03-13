package com.example.handler;

import java.util.*;

import javax.smartcardio.Card;

import msg.GameMessage;
import msg.GameMessage.*;

import com.example.model.PendingAction;
import com.example.model.Player;

/**
 * 游戏逻辑控制器
 * 职责：维护麻将牌池唯一性，控制回合流转、动作校验与数值结算
 */
public class GameController {
    private int currentActionSeat = 0;  // 记录当前行动的座位号
    private List<CardInfo> deck; 
    private CardInfo lastDiscardedCard; // 记录最后一张被打出的牌，用于判定碰杠胡
    private int lastDiscarderSeat = -1; // 记录最后打出牌的人，用于点炮追责
    private List<CardInfo> globalDiscardedCards = new ArrayList<>();
    private CardInfo currentCaishenCard = null; // 当前局的财神牌信息
    
    private int currentMatchCount = 0;     // 当前已完成或正在进行的局数
    private int currentDealerSeat = 0;     // 当前局的庄家座位号
    
    private Set<String> readyPlayers = new HashSet<>();

    private ActionStateMachine stateMachine = new ActionStateMachine();

    private RoomManager roomManager; // 集成庄家管理器

    /**
     * 整场游戏初始化（仅在房主点击开始时调用一次）
     */
    public void initGameSession(List<String> playerCids) {
        int roomSize = playerCids.size(); // 获取动态人数
        this.roomManager = new RoomManager(roomSize);

        this.currentMatchCount = 0;
        this.currentDealerSeat = 0; // 默认座位 0 首局当庄
        
        for (String cid : playerCids) {
            Player p = MsgDispatcher.onlinePlayers.get(cid);
            if (p != null) p.resetSession();
        }
    }

    /**
     * 随机生成本局财神皮
     * 规则：从万(1)、筒(2)、条(3)的1-9，以及风(4)的1-4中随机抽取一张
     */
    private CardInfo generateRandomCaishen() {
        Random random = new Random();
        int total = random.nextInt(31); // 0-30 共31张牌
        int type = total / 9 + 1; // 1-4
        int val = total % 9 + 1;
        
        return CardInfo.newBuilder().setType(type).setValue(val).build();
    }

    /**
     * 单局初始化（每打完一盘回合结束调用一次）
     */
    public void startNewMatch(List<String> playerCids) {
        this.currentMatchCount++;
        // 使用 Dealer 类创建麻将牌堆
        this.deck = Dealer.createMahjongDeck();
        Collections.shuffle(this.deck);
        this.lastDiscardedCard = null;
        this.lastDiscarderSeat = -1;
        this.globalDiscardedCards.clear();

        this.currentCaishenCard = generateRandomCaishen();
        System.out.println("【游戏控制】本局财神产生：类型 " + currentCaishenCard.getType() + "，数值 " + currentCaishenCard.getValue());
        
        // 开局行动者必须严格从 roomManager 获取当前最新计算出的庄家位！
        if (this.roomManager != null) {
            this.currentActionSeat = this.roomManager.getCurrentZhuangSeat();
        } else {
            this.currentActionSeat = 0;
        }

        // 清理玩家上一局的临时数据，保留总分
        for (String cid : playerCids) {
            Player p = MsgDispatcher.onlinePlayers.get(cid);
            if (p != null) p.clearMatchData();
        }

        System.out.println("【游戏控制】对局开始，最新庄家座位：" + this.currentActionSeat);
    }

    /**
     * 判定整场游戏（Session）是否彻底结束
     * 逻辑：调用 RoomManager 判定是否打满了 3 圈（或者达到总庄位数限制）
     */
    public boolean isGameSessionOver() {
        // 安全检查，防范未初始化
        if (this.roomManager == null) {
            return false;
        }
        
        // 直接调用 RoomManager 中定义的判定逻辑
        // totalDealerShift 代表全场产生的第几个庄，当这个数字超过 (人数 * 3) 时，说明 3 圈已满
        return this.roomManager.isGameOver();
    }

    /**
     * 单局结束时的庄家轮换逻辑
     * @param winnerSeat 本局胡牌的赢家（如果是流局传 -1）
     * @param totalPlayers 房间总人数
     */
    public void finishCurrentMatch(int winnerSeat, int totalPlayers) {
        // 严谨的数值安全兜底：防范传入异常人数（如 0 或负数），默认保底为 4
        int safeTotalPlayers = totalPlayers > 0 ? totalPlayers : 4;

        // 如果不是庄家胡牌或者流局，庄家位下移
        if (winnerSeat != currentDealerSeat) {
            currentDealerSeat = (currentDealerSeat + 1) % safeTotalPlayers;
        }
    }

    /**
     * 标记玩家已准备好进入下一局
     */
    public void playerReadyForNextMatch(String cid) {
        this.readyPlayers.add(cid);
    }

    /**
     * 检查是否所有玩家都已准备完毕
     */
    public boolean isAllReadyForNextMatch(int roomSize) {
        return this.readyPlayers.size() >= roomSize;
    }

    /**
     * 清理准备状态，用于新一局发牌前
     */
    public void clearReadyState() {
        this.readyPlayers.clear();
    }

    /**
     * 构建单局小结数据包 (RoundSummary) - 用于【流局/荒庄】
     */
    public RoundSummary buildRoundSummary(int winnerSeat, String winType, List<String> playerCids) {
        RoundSummary.Builder builder = RoundSummary.newBuilder();
        
        builder.setWinnerSeat(winnerSeat == -1 ? -1 : winnerSeat);
        builder.setWinType(winType == null ? "流局" : winType);
        builder.setTotalFan(0); // 流局默认 0 番

        for (String cid : playerCids) {
            Player p = MsgDispatcher.onlinePlayers.get(cid);
            if (p != null) {
                // 流局不加分不扣分
                int scoreChange = 0; 
                
                builder.addScores(PlayerRoundScore.newBuilder()
                        .setSeatIndex(p.getSeatIndex())
                        .setNickname(p.getNickname() == null ? "未知玩家" : p.getNickname())
                        .setScoreChange(scoreChange)
                        .setCurrentTotalScore(p.getScore())
                        .build());
            }
        }

        // 触发轮庄
        int safePlayerCount = (playerCids == null || playerCids.isEmpty()) ? 4 : playerCids.size();
        finishCurrentMatch(winnerSeat, safePlayerCount);

        return builder.build();
    }

    /**
     * 出牌后的状态流转逻辑 (对接状态机)
     */
    public void handleDiscardAction(int seatIndex, CardInfo card, int totalPlayers) {
        // 1. 记录物理出牌数据
        this.lastDiscardedCard = card;
        this.lastDiscarderSeat = seatIndex; 
        this.globalDiscardedCards.add(card);
        
        // 2. 将控制权交给状态机，开启拦截窗口
        int safePlayerCount = Math.max(2, totalPlayers);
        this.stateMachine.startInterceptWindow(safePlayerCount, this.lastDiscarderSeat);
        
        // 注意：此时不能直接修改 currentActionSeat 交给下一个人，
        // 必须等状态机 resolveHighestPriorityAction 执行完毕后，再决定下一个是谁。
    }

    /**
     * 接收拦截动作，并在收集完毕后执行最终结果
     */
    public synchronized boolean receiveInterceptAction(int seatIndex, int actionCode, int totalFan, List<String> fanNames, List<CardInfo> extraCards, Map<String, Player> onlinePlayers, List<String> roomPlayers) {
        
        // 【核心护盾】防御重复包与非法渗透
        if (!this.stateMachine.isIntercepting()) {
            System.out.println("【系统护盾】当前不在拦截等待状态，已丢弃玩家 " + seatIndex + " 的迟到/非法动作: " + actionCode);
            return false; // 瞬间拔掉网线，绝对不往下执行任何结算与发牌逻辑！
        }

        // 1. 把动作丢给状态机记录
        this.stateMachine.receiveAction(seatIndex, actionCode, totalFan, fanNames, extraCards);

        // 2. 检查状态机是否已经收集完毕并关闭了窗口
        if (!this.stateMachine.isIntercepting()) {
            System.out.println("【状态机裁决】由玩家 " + seatIndex + " 的操作完成了最终收集！开始执行优先级比较...");

            // 3. 拿取最终胜出的动作
            PendingAction finalAction = this.stateMachine.resolveHighestPriorityAction();

            if (finalAction == null) {
                // ==========================================
                // 场景 A：所有人都点了“过” (无人拦截)
                // ==========================================
                int safeRoomSize = Math.max(2, roomPlayers == null ? 4 : roomPlayers.size()); // 最小值是 2 防止溢出
                int nextSeat = (Math.max(0, this.lastDiscarderSeat) + 1) % safeRoomSize; // 严格的负数检测，防止溢出
                this.currentActionSeat = nextSeat;
                
                System.out.println("【游戏流转】无人拦截，回合顺延至下家: 座位 " + this.currentActionSeat);
                
                // 下家自动摸一张牌
                Player nextPlayer = getPlayerBySeat(this.currentActionSeat, onlinePlayers, roomPlayers);
                if (nextPlayer != null) {
                    CardInfo newDrawnCard = drawOneCard(); 
                    
                    if (newDrawnCard != null) {
                        // 牌山还有牌，发给下家
                        nextPlayer.getHandCards().add(newDrawnCard);
                        this.lastDiscardedCard = null; // 清理浮空牌
                        return true; // 返回 true，允许外层发送 1005 桌面同步
                        
                    } else {
                        // ==========================================
                        // 牌山耗尽
                        System.out.println("【单局结束】牌堆已完全抽空！");
                        
                        // 1. 调用我们刚写的流局方法，群发 1008 战报
                        handleDrawGame(onlinePlayers, roomPlayers);
                        
                        // 2. 清理浮空牌
                        this.lastDiscardedCard = null;
                        
                        // 3. 极其重要：返回 false！
                        // 因为单局已经结束并下发了 1008，绝对不能再让外层去发 1005 状态包了！
                        return false; 
                    }
                }
                
                // 通知下发最新的 1005 桌面状态
                // 注意：这里需要你调用 MsgDispatcher 里的广播方法，将 buildGameStateSync 发给全房间
                
            } else {
                // ==========================================
                // 场景 B：有人成功拦截（碰 / 杠 / 吃 / 胡）
                // ==========================================
                int winnerSeat = Math.max(0, finalAction.seatIndex);
                int actCode = Math.max(0, finalAction.actionCode);
                
                // 拦截成功，剥夺原本下家的摸牌权，把当前回合行动权赋予拦截者
                this.currentActionSeat = winnerSeat;
                Player interceptor = getPlayerBySeat(winnerSeat, onlinePlayers, roomPlayers);

                if (actCode == 4) { 
                    // --- 胡牌 (HU) ---
                    System.out.println("【游戏流转】玩家 " + winnerSeat + " 成功胡牌！进入结算...");
                    
                    // 1. 调用结算核心方法，进行底分和番数的乘法计算
                    msg.GameMessage.RoundSummary summary = processHu(
                            winnerSeat, 
                            onlinePlayers, 
                            roomPlayers, 
                            finalAction.totalFan, 
                            finalAction.fanNames,
                            this.roomManager.getCurrentZhuangSeat(),
                            this.roomManager.getZhuangGameCount()
                    );
                    
                    // 2. 只有在成功生成了战报时，才进行广播下发
                    if (summary != null) {
                        msg.GameMessage.MainMessage msgHu = msg.GameMessage.MainMessage.newBuilder()
                                .setCode(1008)
                                .setRoundSummary(summary)
                                .build();
                        
                        // 群发给房间内的所有客户端
                        for (String roomId : roomPlayers) {
                            Player rp = onlinePlayers.get(roomId);
                            if (rp != null && rp.getChannel().isActive()) {
                                rp.getChannel().writeAndFlush(msgHu);
                            }
                        }
                    }
                    
                    // 胡牌代表单局结束，不需要再下发 1005 状态包了
                    return false;
                } 
                else if (actCode == 2 && interceptor != null) { 
                    // --- 碰牌 (PONG) ---
                    System.out.println("【游戏流转】玩家 " + winnerSeat + " 执行碰牌！");
                    
                    // 第 1 步：将刚才打出的牌从公共牌池 (牌河) 中没收
                    if (this.globalDiscardedCards != null && !this.globalDiscardedCards.isEmpty()) {
                        this.globalDiscardedCards.remove(this.globalDiscardedCards.size() - 1);
                    }
                    
                    // 第 2 步：从拦截者的手牌中扣除 2 张相同类型的牌
                    if (interceptor.getHandCards() != null && this.lastDiscardedCard != null) {
                        int removeCount = 0;
                        Iterator<CardInfo> iterator = interceptor.getHandCards().iterator();
                        while (iterator.hasNext()) {
                            CardInfo c = iterator.next();
                            // 安全比较，防范空指针
                            int type1 = c.getType();
                            int val1 = c.getValue();
                            int type2 = this.lastDiscardedCard.getType();
                            int val2 = this.lastDiscardedCard.getValue();
                            
                            if (type1 == type2 && val1 == val2) {
                                iterator.remove();
                                removeCount++;
                                if (removeCount == 2) break; // 扣除两张即可
                            }
                        }
                    }
                    
                    // 第 3 步：组装一个“成牌组合 (CardSet)”，塞入玩家的成牌区
                    if (this.lastDiscardedCard != null) {
                        GameMessage.CardSet pongSet = GameMessage.CardSet.newBuilder()
                                .setType(ActionType.PONG) 
                                .addCards(this.lastDiscardedCard) // 被碰的牌
                                .addCards(this.lastDiscardedCard) // 自己手里的第一张
                                .addCards(this.lastDiscardedCard) // 自己手里的第二张
                                .build();
                        
                        if (interceptor.getFormedSets() != null) {
                            interceptor.getFormedSets().add(pongSet);
                        }
                    }

                    sortCards(interceptor.getHandCards());
                    
                    // 第 4 步：状态同步
                    // 碰牌后，玩家不需要摸牌，直接进入出牌阶段
                    // 通知下发最新的 1005 桌面状态
                }
                else if (actCode == 3 && interceptor != null) { 
                    // --- 明杠 (KONG) ---
                    System.out.println("【游戏流转】玩家 " + winnerSeat + " 执行明杠！");
                    
                    // 第 1 步：将刚才打出的牌从公共牌池 (牌河) 中没收
                    if (this.globalDiscardedCards != null && !this.globalDiscardedCards.isEmpty()) {
                        this.globalDiscardedCards.remove(this.globalDiscardedCards.size() - 1);
                    }
                    
                    // 第 2 步：从拦截者的手牌中扣除 3 张相同类型的牌
                    if (interceptor.getHandCards() != null && this.lastDiscardedCard != null) {
                        int removeCount = 0;
                        Iterator<CardInfo> iterator = interceptor.getHandCards().iterator();
                        while (iterator.hasNext()) {
                            CardInfo c = iterator.next();
                            int type1 = Math.max(0, c.getType());
                            int val1 = Math.max(0, c.getValue());
                            int type2 = Math.max(0, this.lastDiscardedCard.getType());
                            int val2 = Math.max(0, this.lastDiscardedCard.getValue());
                            
                            if (type1 == type2 && val1 == val2) {
                                iterator.remove();
                                removeCount++;
                                if (removeCount == 3) break; // 明杠需要扣除3张
                            }
                        }
                    }
                    
                    // 第 3 步：组装成牌组合 (4张牌)
                    if (this.lastDiscardedCard != null) {
                        GameMessage.CardSet kongSet = GameMessage.CardSet.newBuilder()
                                .setType(ActionType.KONG)
                                .addCards(this.lastDiscardedCard)
                                .addCards(this.lastDiscardedCard)
                                .addCards(this.lastDiscardedCard)
                                .addCards(this.lastDiscardedCard)
                                .build();
                        
                        if (interceptor.getFormedSets() != null) {
                            interceptor.getFormedSets().add(kongSet);
                        }
                    }

                    sortCards(interceptor.getHandCards());
                    
                    // 第 4 步：杠牌特权 —— 必须摸一张岭上牌（补牌）
                    // 杠完之后手牌数量必须恢复到 3N+2 的状态才能继续出牌
                    CardInfo replacementCard = drawOneCard();
                    if (replacementCard != null && interceptor.getHandCards() != null) {
                        interceptor.getHandCards().add(replacementCard);
                    }
                    System.out.println("【游戏流转】杠牌完成，已发放补牌。");
                } 
                else if (actCode == 5 && interceptor != null) { 
                    // --- 吃牌 (CHI) ---
                    System.out.println("【游戏流转】玩家 " + winnerSeat + " 执行吃牌！");
                    
                    // 第 1 步：没收牌河里的牌
                    if (this.globalDiscardedCards != null && !this.globalDiscardedCards.isEmpty()) {
                        this.globalDiscardedCards.remove(this.globalDiscardedCards.size() - 1);
                    }
                    
                    // 第 2 步：精准扣除用于吃牌的那两张手牌
                    List<CardInfo> chiCards = finalAction.extraCards;
                    if (interceptor.getHandCards() != null && chiCards != null && chiCards.size() == 2) {
                        for (CardInfo target : chiCards) {
                            int targetType = Math.max(0, target.getType());
                            int targetVal = Math.max(0, target.getValue());
                            
                            Iterator<CardInfo> iterator = interceptor.getHandCards().iterator();
                            while (iterator.hasNext()) {
                                CardInfo c = iterator.next();
                                if (Math.max(0, c.getType()) == targetType && Math.max(0, c.getValue()) == targetVal) {
                                    iterator.remove();
                                    break; // 找到对应的一张牌后立刻跳出内循环，去扣除下一张
                                }
                            }
                        }
                    }
                    
                    // 第 3 步：组装吃牌组合，按照顺序摆放 (参数牌1 + 目标牌 + 参数牌2)
                    // 前端可以在组装 actionData 时就把顺序排好，这里直接按序塞入
                    if (this.lastDiscardedCard != null && chiCards != null && chiCards.size() == 2) {
                        // 严格按照递增顺序来构建吃牌组合
                        List<CardInfo> chiSort = new ArrayList<>(chiCards);
                        chiSort.add(this.lastDiscardedCard);
                        sortCards(chiSort);
                        GameMessage.CardSet chiSet = GameMessage.CardSet.newBuilder()
                                .setType(ActionType.CHI)
                                .addAllCards(chiSort)
                                .build();
                        
                        if (interceptor.getFormedSets() != null) {
                            interceptor.getFormedSets().add(chiSet);
                        }
                    }

                    sortCards(interceptor.getHandCards());
                }
            }

            this.lastDiscardedCard = null;
            
            // 清空状态机数据，迎接下一个回合
            this.stateMachine.resetMachine();

            return true;
        }

        // 还没收集满，当前线程的使命结束，返回 false
        return false;
    }

    /**
     * 温州麻将结算核心
     * @param zhuangSeat 当前庄家座位号
     * @param zhuangGameCount 庄局数 (1, 2, 3)
     */
    public RoundSummary processHu(int winnerSeat, Map<String, Player> onlinePlayers, List<String> roomPlayers, 
                                int totalFan, List<String> fanNames, int zhuangSeat, int zhuangGameCount) {
        
        Player winner = getPlayerBySeat(winnerSeat, onlinePlayers, roomPlayers);
        if (winner == null) return null;

        // 1. 基础番数与赢家判定
        boolean isZimo = (winner.getHandCards().size() % 3 == 2);
        boolean isThreeJokerWin = fanNames.contains("三财神"); // 假设前端算法传来的番型包含此项
        
        RoundSummary.Builder summaryBuilder = RoundSummary.newBuilder()
                .setWinnerSeat(winnerSeat)
                .setWinType(isZimo ? "自摸" : "点炮")
                .setTotalFan(totalFan)
                .addAllFanNames(fanNames == null ? new ArrayList<>() : fanNames);

        // 补齐副露数据
        if (winner.getFormedSets() != null) {
            summaryBuilder.addAllWinnerMelds(winner.getFormedSets());
        }

        // 剥离并补齐手牌与胡的那张牌
        List<CardInfo> handCardsToSync = new ArrayList<>(winner.getHandCards());
        if (isZimo && !handCardsToSync.isEmpty()) {
            // 自摸：最后一张摸到的牌就是胡的牌
            CardInfo winCard = handCardsToSync.remove(handCardsToSync.size() - 1);
            summaryBuilder.setWinningCard(winCard);
        } else {
            // 点炮：牌河里最后打出的那张就是胡的牌
            if (this.lastDiscardedCard != null) {
                summaryBuilder.setWinningCard(this.lastDiscardedCard);
            }
        }
        summaryBuilder.addAllWinnerHandCards(handCardsToSync);

        // 2. 准备结算映射 (SeatIndex -> 变动分数)
        Map<Integer, Integer> scoreChanges = new HashMap<>();
        for (String cid : roomPlayers) {
            scoreChanges.put(onlinePlayers.get(cid).getSeatIndex(), 0);
        }

        // ==========================================
        // 逻辑 A：基础胡牌分数计算 (所有人都要给赢家钱)
        // ==========================================
        int baseScore = 1; 
        int multi = 1;
        for (int i = 0; i < totalFan; i++) multi *= 2;
        int totalScore = baseScore * multi;

        for (String cid : roomPlayers) {
            Player p = onlinePlayers.get(cid);
            int currentPSeat = p.getSeatIndex();
            if (currentPSeat == winnerSeat) continue;

            int multiplier = 1;
            // 第1庄=2倍，第2庄=4倍，第3庄=8倍。
            if (currentPSeat == zhuangSeat || winnerSeat == zhuangSeat) {
                multiplier = (int) Math.pow(2, zhuangGameCount);
            }

            int huScore = totalScore * multiplier;
            
            scoreChanges.put(currentPSeat, scoreChanges.get(currentPSeat) - huScore);
            scoreChanges.put(winnerSeat, scoreChanges.get(winnerSeat) + huScore);
        }

        // ==========================================
        // 逻辑 B：财神额外分结算 (无论胡否，每张财神收所有人1分)
        // ==========================================
        for (String cid : roomPlayers) {
            Player p = onlinePlayers.get(cid);
            int jokerInHand = countJokers(p.getHandCards());
            if (jokerInHand <= 0) continue;

            // 如果是三财神胡牌，财神分翻倍 (1分变2分)
            int scorePerJoker = isThreeJokerWin ? 2 : 1;
            int singleJokerBonus = jokerInHand * scorePerJoker;

            // 每个人都要给这个持有者 singleJokerBonus 分
            for (String otherCid : roomPlayers) {
                Player other = onlinePlayers.get(otherCid);
                if (other.getSeatIndex() == p.getSeatIndex()) continue;

                scoreChanges.put(other.getSeatIndex(), scoreChanges.get(other.getSeatIndex()) - singleJokerBonus);
                scoreChanges.put(p.getSeatIndex(), scoreChanges.get(p.getSeatIndex()) + singleJokerBonus);
            }
        }

        // 3. 写入最终分数变动并封装 Protobuf
        for (String cid : roomPlayers) {
            Player p = onlinePlayers.get(cid);
            int finalChange = scoreChanges.get(p.getSeatIndex());
            p.setScore(p.getScore() + finalChange);

            summaryBuilder.addScores(PlayerRoundScore.newBuilder()
                    .setSeatIndex(p.getSeatIndex())
                    .setNickname(p.getNickname())
                    .setScoreChange(finalChange)
                    .setCurrentTotalScore(p.getScore())
                    .build());
        }

        // 单局分数结算完毕后，必须触发庄家状态机的流转！
        if (this.roomManager != null) {
            this.roomManager.updateZhuangAfterRound(winnerSeat);
        }

        return summaryBuilder.build();
    }

    /** 统计财神数量 (数据白板 4-5) */
    private int countJokers(List<CardInfo> cards) {
        int count = 0;
        for (CardInfo c : cards) {
            if (c.getType() == 4 && c.getValue() == 5) count++;
        }
        return count;
    }

    /**
     * 处理流局（牌山触底，无人胡牌）的终点逻辑
     */
    public void handleDrawGame(Map<String, Player> onlinePlayers, List<String> roomPlayers) {
        System.out.println("【游戏流转】牌山触及底线，触发流局！生成结算战报...");

        // 1. 构建流局的战报，明确 winnerSeat 为 -1
        RoundSummary.Builder summaryBuilder = RoundSummary.newBuilder()
                .setWinnerSeat(-1) 
                .setWinType("流局")
                .setTotalFan(0);

        // TODO 扩展点 1：流局财神分计算
        // 未来如果需要“流局也要算手里财神钱”，可以在这里增加一个循环。
        // 逻辑：遍历所有人手里的财神数量，互相加减分，并记录到 scoreChange 中。

        // 2. 遍历实际在场玩家，构建分数信息（当前流局分数为 0）
        for (String roomId : roomPlayers) {
            Player p = onlinePlayers.get(roomId); 
            if (p != null) {
                int scoreChange = 0; // 如果实现了 TODO 1，这里填入结算后的财神分
                
                // 更新玩家总分
                p.setScore(p.getScore() + scoreChange);

                PlayerRoundScore.Builder scoreBuilder = PlayerRoundScore.newBuilder()
                        .setSeatIndex(p.getSeatIndex())
                        .setNickname(p.getNickname() != null ? p.getNickname() : "")
                        .setScoreChange(scoreChange) 
                        .setCurrentTotalScore(p.getScore());
                
                summaryBuilder.addScores(scoreBuilder.build());
            }
        }

        // 3. 包装成 1008 结算协议
        MainMessage msgDraw = MainMessage.newBuilder()
                .setCode(1008)
                .setRoundSummary(summaryBuilder.build())
                .build();

        // 4. 全服群发战报
        for (String roomId : roomPlayers) {
            Player rp = onlinePlayers.get(roomId);
            if (rp != null && rp.getChannel().isActive()) {
                rp.getChannel().writeAndFlush(msgDraw);
            }
        }

        // TODO 扩展点 2：荒庄轮庄规则判定
        // 未来可在此判断：如果本局有人杠牌（遍历玩家的 fixedSets 查找 ActionType.KONG），则庄家继续连庄（传当前庄家座位号）。
        // 目前默认逻辑：流局直接轮庄给下家，直接传 -1 即可让 RoomManager 自动轮转。
        if (this.roomManager != null) {
            this.roomManager.updateZhuangAfterRound(-1);
        }
    }

    /**
     * 处理玩家在自己回合内发起的主动杠牌 (暗杠 / 补杠)
     * @return boolean 是否杠牌成功（成功则通知 MsgDispatcher 广播）
     */
    public boolean processSelfKong(int seatIndex, CardInfo targetCard, Map<String, Player> onlinePlayers, List<String> roomPlayers) {
        // 1. 极致的安全防范
        if (targetCard == null) return false;
        Player player = getPlayerBySeat(seatIndex, onlinePlayers, roomPlayers);
        if (player == null || player.getHandCards() == null) return false;

        // 2. 统计手中目标牌的数量
        int handMatchCount = 0;
        for (CardInfo c : player.getHandCards()) {
            if (c.getType() == targetCard.getType() && c.getValue() == targetCard.getValue()) {
                handMatchCount++;
            }
        }

        boolean isKongSuccess = false;

        // 3. 分支执行逻辑
        if (handMatchCount == 4) {
            // ==========================================
            // 执行暗杠数据变动
            // ==========================================
            System.out.println("【后端执行】玩家 " + seatIndex + " 触发暗杠！");
            
            // 步骤 A: 安全扣除 4 张手牌
            int removeCount = 0;
            Iterator<CardInfo> iterator = player.getHandCards().iterator();
            while (iterator.hasNext()) {
                CardInfo c = iterator.next();
                if (c.getType() == targetCard.getType() && c.getValue() == targetCard.getValue()) {
                    iterator.remove();
                    removeCount++;
                    if (removeCount == 4) break;
                }
            }

            // 步骤 B: 组装暗杠 CardSet 并放入成牌区
            msg.GameMessage.CardSet anKongSet = msg.GameMessage.CardSet.newBuilder()
                    .setType(msg.GameMessage.ActionType.KONG)
                    .addCards(targetCard).addCards(targetCard)
                    .addCards(targetCard).addCards(targetCard)
                    .build();
            
            if (player.getFormedSets() != null) {
                player.getFormedSets().add(anKongSet);
                isKongSuccess = true;
            }
            
        } else if (handMatchCount >= 1 && player.getFormedSets() != null) {
            // ==========================================
            // 执行补杠数据变动
            // ==========================================
            // 步骤 A: 遍历寻找对应的碰牌组合
            for (int i = 0; i < player.getFormedSets().size(); i++) {
                msg.GameMessage.CardSet set = player.getFormedSets().get(i);
                
                if (set.getType() == msg.GameMessage.ActionType.PONG && set.getCardsCount() > 0) {
                    CardInfo setCard = set.getCards(0);
                    
                    if (setCard.getType() == targetCard.getType() && setCard.getValue() == targetCard.getValue()) {
                        System.out.println("【后端执行】玩家 " + seatIndex + " 触发补杠！");
                        
                        // 步骤 B: 从手牌扣除那 1 张用于补杠的牌
                        Iterator<CardInfo> iterator = player.getHandCards().iterator();
                        while (iterator.hasNext()) {
                            CardInfo c = iterator.next();
                            if (c.getType() == targetCard.getType() && c.getValue() == targetCard.getValue()) {
                                iterator.remove();
                                break; 
                            }
                        }

                        // 步骤 C: Protobuf 对象是不可变的，必须创建一个新的 KONG 覆盖原有的 PONG
                        msg.GameMessage.CardSet buKongSet = msg.GameMessage.CardSet.newBuilder()
                                .setType(msg.GameMessage.ActionType.KONG)
                                .addAllCards(set.getCardsList()) // 把原来碰的3张牌加进来
                                .addCards(targetCard)            // 加上第4张牌
                                .build();
                        
                        // 替换原数组中的 PONG 组合
                        player.getFormedSets().set(i, buKongSet);
                        isKongSuccess = true;
                        break;
                    }
                }
            }
        }

        // 4. 发放岭上补牌与状态返回
        if (isKongSuccess) {
            CardInfo replacementCard = drawOneCard();
            if (replacementCard != null) {
                player.getHandCards().add(replacementCard);
                System.out.println("【后端执行】已向玩家发放岭上补牌。");
            }
            return true; 
        }

        return false;
    }

    /**
     * 底层手牌严格排序器
     */
    private void sortCards(List<CardInfo> cards) {
        if (cards == null || cards.isEmpty()) return;
        cards.sort((c1, c2) -> {
            if (c1.getType() != c2.getType()) {
                return Integer.compare(c1.getType(), c2.getType());
            }
            return Integer.compare(c1.getValue(), c2.getValue());
        });
    }

    /**
     * 构建状态同步消息包 (1005)
     */
    public MainMessage buildGameStateSync(List<String> playerCids) {
        GameStateSync.Builder syncBuilder = GameStateSync.newBuilder();

        // 动态同步人数相关的状态
        int roomSize = playerCids.size();
        syncBuilder.setCurrentMatchCount(this.roomManager.getTotalDealerShift());
        syncBuilder.setTotalMatchCount(roomSize * 3);
    
        // 1. 同步回合与牌山
        syncBuilder.setCurrentActionSeat(this.currentActionSeat);
        int displayRemain = Math.max(0, (this.deck == null ? 0 : this.deck.size()) - 16);
        syncBuilder.setRemainingCardsCount(displayRemain);
        
        // 2. 同步温州麻将特有的庄家与圈数信息
        syncBuilder.setCurrentMatchCount(this.roomManager.getTotalDealerShift());
        syncBuilder.setZhuangSeat(this.roomManager.getCurrentZhuangSeat());    // 谁是庄
        syncBuilder.setZhuangGameCount(this.roomManager.getZhuangGameCount()); // 第几（连）庄 (1, 2, 3)
        syncBuilder.setCurrentMatchCount(this.roomManager.getRoundCount());    // 当前第几个庄
        
        // 3. 同步财神皮 (由后端在单局开始时选定，假设已存在 this.currentCaishenCard)
        if (this.currentCaishenCard != null) {
            syncBuilder.setCaishenCard(this.currentCaishenCard);
        }
        
        if (this.lastDiscardedCard != null) {
            syncBuilder.setLastDiscardedCard(this.lastDiscardedCard);
        }

        if (!this.globalDiscardedCards.isEmpty()) {
            syncBuilder.addAllGlobalDiscardedCards(this.globalDiscardedCards);
        }

        for (String cid : playerCids) {
            Player p = MsgDispatcher.onlinePlayers.get(cid);
            if (p != null) {
                syncBuilder.addPlayers(PlayerGameInfo.newBuilder()
                        .setNickname(p.getNickname() == null ? "未知玩家" : p.getNickname())
                        .setSeatIndex(p.getSeatIndex())
                        .setScore(p.getScore())
                        .addAllHandCards(p.getHandCards() == null ? new ArrayList<>() : p.getHandCards())
                        .addAllFixedSets(p.getFormedSets() == null ? new ArrayList<>() : p.getFormedSets()) 
                        .addAllDiscardedCards(p.getDiscardedCards() == null ? new ArrayList<>() : p.getDiscardedCards())
                        .build());
            }
        }

        return MainMessage.newBuilder()
                .setCode(1005)
                .setGameState(syncBuilder.build())
                .build();
    }

    /**
     * 物理抽牌逻辑
     */
    public CardInfo drawOneCard() {
        // 【修改】当牌堆剩余数量大于 16 时，才允许摸牌
        if (deck != null && deck.size() > 16) {
            return deck.remove(0);
        }
        return null; // 触发流局
    }

    /**
     * 辅助方法：根据座位号极其安全地查找玩家
     */
    private Player getPlayerBySeat(int seatIndex, Map<String, Player> onlinePlayers, List<String> roomPlayers) {
        int safeSeat = Math.max(0, seatIndex);
        if (roomPlayers == null || onlinePlayers == null) return null;
        
        for (String cid : roomPlayers) {
            Player p = onlinePlayers.get(cid);
            if (p != null && p.getSeatIndex() == safeSeat) {
                return p;
            }
        }
        return null;
    }

    // --- 属性访问器 ---
    public int getCurrentActionSeat() { return currentActionSeat; }
    public void setCurrentActionSeat(int seat) { this.currentActionSeat = seat; }
    public List<CardInfo> getDeck() { return deck == null ? new ArrayList<>() : deck; }
    public CardInfo getLastDiscardedCard() { return lastDiscardedCard; }
    public int getCurrentMatchCount() { return currentMatchCount; }
    public ActionStateMachine getStateMachine() { return stateMachine; }
    public RoomManager getRoomManager() { return this.roomManager; }
}