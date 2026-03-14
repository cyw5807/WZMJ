package com.example.handler;

import java.util.*;

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

    private ActionStateMachine stateMachine = new ActionStateMachine(this);

    private RoomManager roomManager; // 集成庄家管理器

    /**
     * 整场游戏初始化（仅在房主点击开始时调用一次）
     */
    public void initGameSession(List<String> playerCids) {
        int roomSize = playerCids.size(); 
        this.roomManager = new RoomManager(roomSize);

        this.currentMatchCount = 0;
        this.currentDealerSeat = 0; 
        
        for (String cid : playerCids) {
            Player p = MsgDispatcher.onlinePlayers.get(cid);
            if (p != null) p.resetSession();
        }
    }

    /**
     * 随机生成本局财神皮
     */
    private CardInfo generateRandomCaishen() {
        Random random = new Random();
        int total = random.nextInt(31); 
        int type = total / 9 + 1; 
        int val = total % 9 + 1;
        
        return CardInfo.newBuilder().setType(type).setValue(val).build();
    }

    /**
     * 单局初始化
     */
    public void startNewMatch(List<String> playerCids) {
        this.currentMatchCount++;
        this.deck = Dealer.createMahjongDeck();
        Collections.shuffle(this.deck);
        this.lastDiscardedCard = null;
        this.lastDiscarderSeat = -1;
        this.globalDiscardedCards.clear();

        this.currentCaishenCard = generateRandomCaishen();
        System.out.println("【游戏控制】本局财神产生：类型 " + currentCaishenCard.getType() + "，数值 " + currentCaishenCard.getValue());
        
        if (this.roomManager != null) {
            this.currentActionSeat = this.roomManager.getCurrentZhuangSeat();
        } else {
            this.currentActionSeat = 0;
        }

        for (String cid : playerCids) {
            Player p = MsgDispatcher.onlinePlayers.get(cid);
            if (p != null) p.clearMatchData();
        }

        System.out.println("【游戏控制】对局开始，最新庄家座位：" + this.currentActionSeat);
    }

    public boolean isGameSessionOver() {
        if (this.roomManager == null) return false;
        return this.roomManager.isGameOver();
    }

    public void finishCurrentMatch(int winnerSeat, int totalPlayers) {
        int safeTotalPlayers = totalPlayers > 0 ? totalPlayers : 4;
        if (winnerSeat != currentDealerSeat) {
            currentDealerSeat = (currentDealerSeat + 1) % safeTotalPlayers;
        }
    }

    public void playerReadyForNextMatch(String cid) {
        this.readyPlayers.add(cid);
    }

    public boolean isAllReadyForNextMatch(int roomSize) {
        return this.readyPlayers.size() >= roomSize;
    }

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
        builder.setTotalFan(0); 

        for (String cid : playerCids) {
            Player p = MsgDispatcher.onlinePlayers.get(cid);
            if (p != null) {
                int scoreChange = 0; 
                builder.addScores(PlayerRoundScore.newBuilder()
                        .setSeatIndex(p.getSeatIndex())
                        .setNickname(p.getNickname() == null ? "未知玩家" : p.getNickname())
                        .setScoreChange(scoreChange)
                        .setCurrentTotalScore(p.getScore())
                        .build());
            }
        }

        int safePlayerCount = (playerCids == null || playerCids.isEmpty()) ? 4 : playerCids.size();
        finishCurrentMatch(winnerSeat, safePlayerCount);

        return builder.build();
    }

    /**
     * 出牌后的状态流转逻辑
     */
    public void handleDiscardAction(int seatIndex, CardInfo card, int totalPlayers) {
        this.lastDiscardedCard = card;
        this.lastDiscarderSeat = seatIndex; 
        this.globalDiscardedCards.add(card);
        
        int safePlayerCount = Math.max(2, totalPlayers);
        
        this.stateMachine.startInterceptWindow(safePlayerCount, this.lastDiscarderSeat, card, null);
    }

    /**
     * 极简化的动作入口：只负责把数据喂给状态机
     */
    public synchronized boolean receiveInterceptAction(int seatIndex, int actionCode, int totalFan, List<String> fanNames, List<CardInfo> extraCards, Map<String, Player> onlinePlayers, List<String> roomPlayers) {
        if (!this.stateMachine.isIntercepting()) {
            System.out.println("【系统护盾】当前不在拦截等待状态，丢弃非法动作: " + actionCode);
            return false; 
        }

        // 把动作丢给状态机，它内部会瞬间完成数学截断推演，并自动调用下方的 execute 系列回调函数！
        this.stateMachine.receiveAction(seatIndex, actionCode, totalFan, fanNames, extraCards);
        
        // 返回 false 是因为状态机回调函数内部已经接管了网络广播，外部 MsgDispatcher 不需要再重发了。
        return false;
    }

    // ==========================================
    // 状态机专属回调函数组 (Callbacks)
    // ==========================================

    public void executePong(int seatIndex, CardInfo targetCard) {
        System.out.println("【回调执行】玩家 " + seatIndex + " 执行碰牌！");
        this.currentActionSeat = seatIndex;
        Player p = getPlayerBySeat(seatIndex, MsgDispatcher.onlinePlayers, MsgDispatcher.roomPlayers);
        
        if (this.globalDiscardedCards != null && !this.globalDiscardedCards.isEmpty()) {
            this.globalDiscardedCards.remove(this.globalDiscardedCards.size() - 1);
        }
        
        if (p != null && p.getHandCards() != null) {
            int removeCount = 0;
            Iterator<CardInfo> it = p.getHandCards().iterator();
            while (it.hasNext()) {
                CardInfo c = it.next();
                if (c.getType() == targetCard.getType() && c.getValue() == targetCard.getValue()) {
                    it.remove();
                    removeCount++;
                    if (removeCount == 2) break;
                }
            }
            
            GameMessage.CardSet set = GameMessage.CardSet.newBuilder()
                    .setType(ActionType.PONG)
                    .addCards(targetCard).addCards(targetCard).addCards(targetCard)
                    .setTargetCard(targetCard)
                    .build();
            p.getFormedSets().add(set);
            sortCards(p.getHandCards());
        }
        
        this.lastDiscardedCard = null;
        broadcastGameStateSync();
    }

    public void executeMingGang(int seatIndex, CardInfo targetCard) {
        System.out.println("【回调执行】玩家 " + seatIndex + " 执行明杠！");
        this.currentActionSeat = seatIndex;
        Player p = getPlayerBySeat(seatIndex, MsgDispatcher.onlinePlayers, MsgDispatcher.roomPlayers);
        
        if (this.globalDiscardedCards != null && !this.globalDiscardedCards.isEmpty()) {
            this.globalDiscardedCards.remove(this.globalDiscardedCards.size() - 1);
        }
        
        if (p != null && p.getHandCards() != null) {
            int removeCount = 0;
            Iterator<CardInfo> it = p.getHandCards().iterator();
            while(it.hasNext()) {
                CardInfo c = it.next();
                if (c.getType() == targetCard.getType() && c.getValue() == targetCard.getValue()) {
                    it.remove();
                    removeCount++;
                    if (removeCount == 3) break;
                }
            }
            
            GameMessage.CardSet set = GameMessage.CardSet.newBuilder()
                    .setType(ActionType.MING_GANG)
                    .addCards(targetCard).addCards(targetCard).addCards(targetCard).addCards(targetCard)
                    .setTargetCard(targetCard)
                    .build();
            p.getFormedSets().add(set);
            sortCards(p.getHandCards());
            
            // 杠牌特权：发放岭上牌
            CardInfo replacementCard = drawOneCard();
            if (replacementCard != null) {
                p.getHandCards().add(replacementCard);
                System.out.println("【游戏流转】明杠完成，已发放岭上补牌。");
                this.lastDiscardedCard = null;
                broadcastGameStateSync();
            } else {
                handleDrawGame(MsgDispatcher.onlinePlayers, MsgDispatcher.roomPlayers);
            }
        }
    }

    public void executeChi(int seatIndex, CardInfo targetCard, List<CardInfo> extraCards) {
        System.out.println("【回调执行】玩家 " + seatIndex + " 执行吃牌！");
        this.currentActionSeat = seatIndex;
        Player p = getPlayerBySeat(seatIndex, MsgDispatcher.onlinePlayers, MsgDispatcher.roomPlayers);
        
        if (p == null || extraCards == null || extraCards.size() != 2) return;

        if (this.globalDiscardedCards != null && !this.globalDiscardedCards.isEmpty()) {
            this.globalDiscardedCards.remove(this.globalDiscardedCards.size() - 1);
        }

        for (CardInfo target : extraCards) {
            Iterator<CardInfo> it = p.getHandCards().iterator();
            while (it.hasNext()) {
                CardInfo c = it.next();
                if (c.getType() == target.getType() && c.getValue() == target.getValue()) {
                    it.remove();
                    break; 
                }
            }
        }

        List<CardInfo> chiSort = new ArrayList<>(extraCards);
        chiSort.add(targetCard);
        sortCards(chiSort);

        GameMessage.CardSet set = GameMessage.CardSet.newBuilder()
                .setType(ActionType.CHI)
                .addAllCards(chiSort)
                .setTargetCard(targetCard)
                .build();
        p.getFormedSets().add(set);
        sortCards(p.getHandCards());

        this.lastDiscardedCard = null;
        broadcastGameStateSync();
    }

    public void executeNextPlayerDraw() {
        int safeRoomSize = Math.max(2, MsgDispatcher.roomPlayers.size());
        this.currentActionSeat = (Math.max(0, this.lastDiscarderSeat) + 1) % safeRoomSize;
        System.out.println("【回调执行】回合顺延至下家: 座位 " + this.currentActionSeat);

        Player p = getPlayerBySeat(this.currentActionSeat, MsgDispatcher.onlinePlayers, MsgDispatcher.roomPlayers);
        if (p != null) {
            CardInfo newDrawnCard = drawOneCard();
            if (newDrawnCard != null) {
                p.getHandCards().add(newDrawnCard);
                this.lastDiscardedCard = null;
                broadcastGameStateSync();
            } else {
                System.out.println("【单局结束】牌堆已完全抽空！");
                handleDrawGame(MsgDispatcher.onlinePlayers, MsgDispatcher.roomPlayers);
                this.lastDiscardedCard = null;
            }
        }
    }

    public void executeReplacementDraw(int targetSeat) {
        System.out.println("【回调执行】抢杠胡无人响应，释放补杠挂起，玩家 " + targetSeat + " 摸岭上牌！");
        this.currentActionSeat = targetSeat;

        this.lastDiscardedCard = null;

        Player p = getPlayerBySeat(targetSeat, MsgDispatcher.onlinePlayers, MsgDispatcher.roomPlayers);
        
        if (p != null) {
            CardInfo replacementCard = drawOneCard();
            if (replacementCard != null) {
                p.getHandCards().add(replacementCard);
                broadcastGameStateSync();
            } else {
                handleDrawGame(MsgDispatcher.onlinePlayers, MsgDispatcher.roomPlayers);
            }
        }
    }

    /**
     * 内部辅助广播方法：替代以前在 MsgDispatcher 外部执行的广播
     */
    private void broadcastGameStateSync() {
        MainMessage syncMsg = buildGameStateSync(MsgDispatcher.roomPlayers);
        for (String roomId : MsgDispatcher.roomPlayers) {
            Player rp = MsgDispatcher.onlinePlayers.get(roomId);
            if (rp != null && rp.getChannel().isActive()) {
                rp.getChannel().writeAndFlush(syncMsg);
            }
        }
    }

    // ==========================================
    // 原有的结算计算与基础流程代码
    // ==========================================

    public RoundSummary processHu(int winnerSeat, Map<String, Player> onlinePlayers, List<String> roomPlayers, 
                                int totalFan, List<String> fanNames, int zhuangSeat, int zhuangGameCount,
                                boolean isQiangGang, CardInfo targetCard) {
        
        Player winner = getPlayerBySeat(winnerSeat, onlinePlayers, roomPlayers);
        if (winner == null) return null;

        boolean isZimo = (winner.getHandCards().size() % 3 == 2);
        boolean isThreeJokerWin = fanNames.contains("三财神"); 
        
        String finalWinType = isQiangGang ? "抢杠胡" : (isZimo ? "自摸" : "点炮");

        RoundSummary.Builder summaryBuilder = RoundSummary.newBuilder()
                .setWinnerSeat(winnerSeat)
                .setWinType(finalWinType)
                .setTotalFan(totalFan)
                .addAllFanNames(fanNames == null ? new ArrayList<>() : fanNames);

        if (winner.getFormedSets() != null) {
            summaryBuilder.addAllWinnerMelds(winner.getFormedSets());
        }

        List<CardInfo> handCardsToSync = new ArrayList<>(winner.getHandCards());
        
        if (isQiangGang && targetCard != null) {
            summaryBuilder.setWinningCard(targetCard);
        } else if (isZimo && !handCardsToSync.isEmpty()) {
            CardInfo winCard = handCardsToSync.remove(handCardsToSync.size() - 1);
            summaryBuilder.setWinningCard(winCard);
        } else {
            if (this.lastDiscardedCard != null) {
                summaryBuilder.setWinningCard(this.lastDiscardedCard);
            }
        }
        summaryBuilder.addAllWinnerHandCards(handCardsToSync);

        Map<Integer, Integer> scoreChanges = new HashMap<>();
        for (String cid : roomPlayers) {
            scoreChanges.put(onlinePlayers.get(cid).getSeatIndex(), 0);
        }

        int baseScore = 1; 
        int multi = 1;
        for (int i = 0; i < totalFan; i++) multi *= 2;
        int totalScore = baseScore * multi;

        for (String cid : roomPlayers) {
            Player p = onlinePlayers.get(cid);
            int currentPSeat = p.getSeatIndex();
            if (currentPSeat == winnerSeat) continue;

            int multiplier = 1;
            if (currentPSeat == zhuangSeat || winnerSeat == zhuangSeat) {
                multiplier = (int) Math.pow(2, zhuangGameCount);
            }

            int huScore = totalScore * multiplier;
            
            scoreChanges.put(currentPSeat, scoreChanges.get(currentPSeat) - huScore);
            scoreChanges.put(winnerSeat, scoreChanges.get(winnerSeat) + huScore);
        }

        for (String cid : roomPlayers) {
            Player p = onlinePlayers.get(cid);
            int jokerInHand = countJokers(p.getHandCards());
            if (jokerInHand <= 0) continue;

            int scorePerJoker = isThreeJokerWin ? 2 : 1;
            int singleJokerBonus = jokerInHand * scorePerJoker;

            for (String otherCid : roomPlayers) {
                Player other = onlinePlayers.get(otherCid);
                if (other.getSeatIndex() == p.getSeatIndex()) continue;

                scoreChanges.put(other.getSeatIndex(), scoreChanges.get(other.getSeatIndex()) - singleJokerBonus);
                scoreChanges.put(p.getSeatIndex(), scoreChanges.get(p.getSeatIndex()) + singleJokerBonus);
            }
        }

        for (String cid : roomPlayers) {
            Player p = onlinePlayers.get(cid);
            int finalChange = scoreChanges.get(p.getSeatIndex());
            p.setScore(p.getScore() + finalChange);
            int jCount = countJokers(p.getHandCards());

            summaryBuilder.addScores(msg.GameMessage.PlayerRoundScore.newBuilder()
                    .setSeatIndex(p.getSeatIndex())
                    .setNickname(p.getNickname())
                    .setScoreChange(finalChange)
                    .setCurrentTotalScore(p.getScore())
                    .setJokerCount(jCount)
                    .build());
        }

        if (this.roomManager != null) {
            this.roomManager.updateZhuangAfterRound(winnerSeat);
        }

        return summaryBuilder.build();
    }

    private int countJokers(List<CardInfo> cards) {
        int count = 0;
        for (CardInfo c : cards) {
            if (c.getType() == 4 && c.getValue() == 5) count++;
        }
        return count;
    }

    public void executeInterceptHu(int winnerSeat, CardInfo targetCard, boolean isQiangGang, int totalFan, List<String> fanNames) {
        System.out.println("【状态机回调】玩家 " + winnerSeat + " 成功触发胡牌结算！");
        
        msg.GameMessage.RoundSummary summary = processHu(
                winnerSeat, 
                MsgDispatcher.onlinePlayers, 
                MsgDispatcher.roomPlayers, 
                totalFan, 
                fanNames,
                this.roomManager.getCurrentZhuangSeat(),
                this.roomManager.getZhuangGameCount(),
                isQiangGang, 
                targetCard   
        );
        
        if (summary != null) {
            msg.GameMessage.MainMessage msgHu = msg.GameMessage.MainMessage.newBuilder()
                    .setCode(1008)
                    .setRoundSummary(summary)
                    .build();
            
            for (String roomId : MsgDispatcher.roomPlayers) {
                Player rp = MsgDispatcher.onlinePlayers.get(roomId);
                if (rp != null && rp.getChannel().isActive()) {
                    rp.getChannel().writeAndFlush(msgHu);
                }
            }
        }
    }

    public void handleDrawGame(Map<String, Player> onlinePlayers, List<String> roomPlayers) {
        System.out.println("【游戏流转】牌山触及底线，触发流局！生成结算战报...");

        RoundSummary.Builder summaryBuilder = RoundSummary.newBuilder()
                .setWinnerSeat(-1) 
                .setWinType("流局")
                .setTotalFan(0);

        Map<Integer, Integer> scoreChanges = new HashMap<>();
        for (String cid : roomPlayers) {
            Player p = onlinePlayers.get(cid);
            if (p != null) {
                scoreChanges.put(p.getSeatIndex(), 0);
            }
        }

        boolean hasAnyKong = false;

        for (String cid : roomPlayers) {
            Player p = onlinePlayers.get(cid);
            if (p == null) continue;

            int currentSeat = p.getSeatIndex();

            int jokerCount = 0;
            for (CardInfo c : p.getHandCards()) {
                if (c.getType() == 4 && c.getValue() == 5) {
                    jokerCount++;
                }
            }

            if (jokerCount > 0) {
                for (String otherCid : roomPlayers) {
                    if (otherCid.equals(cid)) continue;
                    Player other = onlinePlayers.get(otherCid);
                    if (other != null) {
                        int otherSeat = other.getSeatIndex();
                        scoreChanges.put(otherSeat, scoreChanges.get(otherSeat) - jokerCount);
                        scoreChanges.put(currentSeat, scoreChanges.get(currentSeat) + jokerCount);
                    }
                }
            }

            if (p.getFormedSets() != null) {
                for (msg.GameMessage.CardSet set : p.getFormedSets()) {
                    if (set.getType() == msg.GameMessage.ActionType.AN_GANG ||
                        set.getType() == msg.GameMessage.ActionType.MING_GANG ||
                        set.getType() == msg.GameMessage.ActionType.BU_GANG) {
                        hasAnyKong = true;
                    }
                }
            }
        }

        for (String cid : roomPlayers) {
            Player p = onlinePlayers.get(cid);
            if (p != null) {
                int finalChange = scoreChanges.getOrDefault(p.getSeatIndex(), 0);
                p.setScore(p.getScore() + finalChange);

                int jCount = 0;
                if (p.getHandCards() != null) {
                    for (CardInfo c : p.getHandCards()) {
                        if (c.getType() == 4 && c.getValue() == 5) jCount++;
                    }
                }

                msg.GameMessage.PlayerRoundScore.Builder scoreBuilder = msg.GameMessage.PlayerRoundScore.newBuilder()
                        .setSeatIndex(p.getSeatIndex())
                        .setNickname(p.getNickname() != null ? p.getNickname() : "")
                        .setScoreChange(finalChange)
                        .setCurrentTotalScore(p.getScore())
                        .setJokerCount(jCount);

                summaryBuilder.addScores(scoreBuilder.build());
            }
        }

        msg.GameMessage.MainMessage msgDraw = msg.GameMessage.MainMessage.newBuilder()
                .setCode(1008)
                .setRoundSummary(summaryBuilder.build())
                .build();

        for (String roomId : roomPlayers) {
            Player rp = onlinePlayers.get(roomId);
            if (rp != null && rp.getChannel().isActive()) {
                rp.getChannel().writeAndFlush(msgDraw);
            }
        }

        if (this.roomManager != null) {
            if (hasAnyKong) {
                System.out.println("【游戏流转】本局存在杠牌记录，触发正常庄家更替...");
                this.roomManager.updateZhuangAfterRound(-1);
            } else {
                System.out.println("【游戏流转】本局无杠牌记录，庄家保持不变，连庄数不增加...");
                this.roomManager.retainZhuangWithoutIncrement();
            }
        }
    }

    public boolean processSelfKong(int seatIndex, CardInfo targetCard, Map<String, Player> onlinePlayers, List<String> roomPlayers) {
        if (targetCard == null) return false;
        Player player = getPlayerBySeat(seatIndex, onlinePlayers, roomPlayers);
        if (player == null || player.getHandCards() == null) return false;

        int handMatchCount = 0;
        for (CardInfo c : player.getHandCards()) {
            if (c.getType() == targetCard.getType() && c.getValue() == targetCard.getValue()) {
                handMatchCount++;
            }
        }

        boolean isKongSuccess = false;
        boolean isAnGangAction = false; 

        if (handMatchCount == 4) {
            System.out.println("【后端执行】玩家 " + seatIndex + " 触发暗杠！");
            isAnGangAction = true;
            
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

            msg.GameMessage.CardSet anKongSet = msg.GameMessage.CardSet.newBuilder()
                    .setType(msg.GameMessage.ActionType.AN_GANG)
                    .addCards(targetCard).addCards(targetCard)
                    .addCards(targetCard).addCards(targetCard)
                    .setTargetCard(targetCard) 
                    .build();
            
            if (player.getFormedSets() != null) {
                player.getFormedSets().add(anKongSet);
                isKongSuccess = true;
            }
            
        } else if (handMatchCount >= 1 && player.getFormedSets() != null) {
            for (int i = 0; i < player.getFormedSets().size(); i++) {
                msg.GameMessage.CardSet set = player.getFormedSets().get(i);
                
                if (set.getType() == msg.GameMessage.ActionType.PONG && set.getCardsCount() > 0) {
                    CardInfo setCard = set.getCards(0);
                    
                    if (setCard.getType() == targetCard.getType() && setCard.getValue() == targetCard.getValue()) {
                        System.out.println("【后端执行】玩家 " + seatIndex + " 触发补杠！");
                        isAnGangAction = false;
                        
                        Iterator<CardInfo> iterator = player.getHandCards().iterator();
                        while (iterator.hasNext()) {
                            CardInfo c = iterator.next();
                            if (c.getType() == targetCard.getType() && c.getValue() == targetCard.getValue()) {
                                iterator.remove();
                                break; 
                            }
                        }

                        msg.GameMessage.CardSet buKongSet = msg.GameMessage.CardSet.newBuilder()
                                .setType(msg.GameMessage.ActionType.BU_GANG)
                                .addAllCards(set.getCardsList()) 
                                .addCards(targetCard)            
                                .setTargetCard(targetCard)       
                                .build();
                        
                        player.getFormedSets().set(i, buKongSet);
                        isKongSuccess = true;
                        break;
                    }
                }
            }
        }

        if (isKongSuccess) {
            if (isAnGangAction) {
                CardInfo replacementCard = drawOneCard();
                if (replacementCard != null) {
                    player.getHandCards().add(replacementCard);
                    System.out.println("【后端执行】暗杠完成，已向玩家发放岭上补牌。");
                }
            } else {
                System.out.println("【后端执行】补杠挂起！暂停发牌，向全服询问是否抢杠胡...");

                this.lastDiscardedCard = targetCard;
                this.lastDiscarderSeat = seatIndex;

                if (this.stateMachine != null) {
                    int safePlayerCount = Math.max(2, roomPlayers.size());
                    this.stateMachine.startQiangGangIntercept(seatIndex, targetCard, safePlayerCount, null);
                }
            }
            return true; 
        }

        return false;
    }

    private void sortCards(List<CardInfo> cards) {
        if (cards == null || cards.isEmpty()) return;

        cards.removeIf(Objects::isNull);

        cards.sort((c1, c2) -> {
            if (c1.getType() != c2.getType()) {
                return Integer.compare(c1.getType(), c2.getType());
            }
            return Integer.compare(c1.getValue(), c2.getValue());
        });
    }

    public MainMessage buildGameStateSync(List<String> playerCids) {
        GameStateSync.Builder syncBuilder = GameStateSync.newBuilder();

        int roomSize = playerCids.size();
        syncBuilder.setCurrentActionSeat(this.currentActionSeat);
        
        int displayRemain = Math.max(0, (this.deck == null ? 0 : this.deck.size()) - 16);
        syncBuilder.setRemainingCardsCount(displayRemain);
        
        syncBuilder.setTotalMatchCount(roomSize * 3);
        syncBuilder.setCurrentMatchCount(this.roomManager.getTotalDealerShift());
        syncBuilder.setZhuangSeat(this.roomManager.getCurrentZhuangSeat());    
        syncBuilder.setZhuangGameCount(this.roomManager.getZhuangGameCount()); 
        
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

    public CardInfo drawOneCard() {
        if (deck != null && deck.size() > 16) {
            return deck.remove(0);
        }
        return null; 
    }

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