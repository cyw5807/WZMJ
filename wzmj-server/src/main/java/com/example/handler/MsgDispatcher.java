package com.example.handler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import com.example.model.Player;
import io.netty.channel.ChannelHandlerContext;
import msg.GameMessage.*;

/**
 * 消息分发器：负责处理所有客户端指令并维护游戏流转
 */
public class MsgDispatcher {
    private static final Map<Integer, CmdHandler> handlers = new HashMap<>();

    public static List<String> roomPlayers = new ArrayList<>();

    // 核心组件：游戏逻辑控制器
    private static final GameController gameController = new GameController();

    // 存储在线玩家：Channel ID -> Player 对象
    public static final Map<String, Player> onlinePlayers = new ConcurrentHashMap<>();

    // 存储加入顺序：第一个元素即为房主
    public static final List<String> playerOrder = new CopyOnWriteArrayList<>();

    static {
        // --- 1001: 登录请求处理 ---
        handlers.put(1001, (ctx, msg) -> {
            String cid = ctx.channel().id().asLongText();
            String nickname = msg.getLoginRequest().getNickname();
            String channelId = ctx.channel().id().asLongText();

            System.out.println("【登录处理】收到请求 - 昵称: " + nickname);

            Player player = new Player(cid, nickname, ctx.channel());
            onlinePlayers.put(channelId, player);
            if (!playerOrder.contains(channelId)) {
                playerOrder.add(channelId);
            }

            // 回复登录成功 (1002)
            MainMessage loginResponse = MainMessage.newBuilder()
                    .setCode(1002)
                    .setLoginResponse(LoginResponse.newBuilder()
                            .setSuccess(true)
                            .setMessage("登录成功")
                            .build())
                    .build();
            ctx.writeAndFlush(loginResponse);

            // 广播玩家列表 (1003)
            broadcastPlayerList();
        });

        // --- 1004: 房主点击“开始游戏” ---
        handlers.put(1004, (ctx, msg) -> {
            String requesterId = ctx.channel().id().asLongText();
            int playerCount = onlinePlayers.size();
            int hostIndex = playerOrder.indexOf(requesterId);
            
            // 校验房主身份与人数限制(2-4人)
            if (hostIndex == 0 && playerCount >= 2 && playerCount <= 4) {
                System.out.println("【流程】房主启动游戏，执行初始化...");

                roomPlayers.clear();
                roomPlayers.addAll(playerOrder);

                // 为玩家分配席位号
                for (int i = 0; i < roomPlayers.size(); i++) {
                    String pCid = roomPlayers.get(i);
                    Player p = onlinePlayers.get(pCid);
                    if (p != null) {
                        p.setSeatIndex(i); 
                        System.out.println("【座位】玩家: " + p.getNickname() + " -> 席位: " + i);
                    }
                }

                // 初始化多局Session
                gameController.initGameSession(roomPlayers);
                // 启动第一局
                gameController.startNewMatch(roomPlayers);

                Map<String, Player> currentRoomPlayers = new HashMap<>();
                roomPlayers.forEach(cid -> currentRoomPlayers.put(cid, onlinePlayers.get(cid)));
                
                // 初始发牌
                int initialZhuang = 0;
                if(gameController.getRoomManager() != null) {
                    initialZhuang = gameController.getRoomManager().getCurrentZhuangSeat();
                }
                Dealer.getInitialDealData(gameController.getDeck(), currentRoomPlayers, initialZhuang);

                System.out.println("【流程】第一局开启，正在同步桌面...");
                broadcastGameState();
                
            } else {
                String reason = (hostIndex != 0) ? "非房主无权启动" : "人数不符";
                System.out.println("【拒绝】启动失败：" + reason);
            }
        });

        // --- 1006: 玩家操作交互 (出牌、摸牌、胡牌等) ---
        handlers.put(1006, (ctx, msg) -> {
            String cid = ctx.channel().id().asLongText();
            Player p = onlinePlayers.get(cid);
            
            if (p == null || !msg.hasActionReq()) return;

            PlayerActionRequest req = msg.getActionReq();
            ActionType action = req.getAction();
            int currentActionSeat = gameController.getCurrentActionSeat();
            int seatIndex = p.getSeatIndex();

            // ==========================================
            // 核心分流：判断是“自己的主动回合”还是“他人的被动回合”
            // ==========================================
            if (seatIndex == currentActionSeat) {
                // ---------------- 场景 A：主动回合 ----------------
                
                if (action == ActionType.HU) {
                    // 1. 自摸胡牌 (主动发起，无并发冲突)
                    System.out.println("【单局结束】玩家 " + p.getNickname() + " 宣告自摸胡牌！进行结算...");
                    
                    int totalFan = req.getTotalFan();
                    List<String> fanNames = req.getFanNamesList() == null ? new ArrayList<>() : req.getFanNamesList();
                    
                    int currentZhuang = 0;
                    int zhuangCount = 1;
                    if (gameController.getRoomManager() != null) {
                        currentZhuang = gameController.getRoomManager().getCurrentZhuangSeat();
                        zhuangCount = gameController.getRoomManager().getZhuangGameCount();
                    }

                    // 【参数修复】：自摸时，isQiangGang 传 false，targetCard 传 null
                    RoundSummary summary = gameController.processHu(
                            seatIndex, onlinePlayers, roomPlayers, totalFan, fanNames,
                            currentZhuang, zhuangCount, false, null
                    );
                    
                    if (summary != null) {
                        MainMessage msgHu = MainMessage.newBuilder()
                                .setCode(1008)
                                .setRoundSummary(summary)
                                .build();

                        for (String roomId : roomPlayers) {
                            Player rp = onlinePlayers.get(roomId);
                            if (rp != null && rp.getChannel().isActive()) {
                                rp.getChannel().writeAndFlush(msgHu);
                            }
                        }
                    }
                } 
                else if (action == ActionType.DISCARD) {
                    // 2. 出牌逻辑
                    CardInfo discardedCard = req.getCard();
                    Iterator<CardInfo> it = p.getHandCards().iterator();
                    while (it.hasNext()) {
                        CardInfo c = it.next();
                        if (c.getType() == discardedCard.getType() && c.getValue() == discardedCard.getValue()) {
                            it.remove(); 
                            break;
                        }
                    }
                    // 处理出牌并触发拦截等待
                    gameController.handleDiscardAction(seatIndex, discardedCard, roomPlayers.size());
                    broadcastGameState();
                } 
                else if (action == ActionType.DRAW) {
                    // 3. 摸牌逻辑 (包含底线流局检测)
                    CardInfo drawnCard = gameController.drawOneCard();
                    if (drawnCard != null) {
                        p.getHandCards().add(drawnCard);
                        broadcastGameState();
                    } else {
                        // 牌山触及 16 张底线，触发专业流局算法
                        gameController.handleDrawGame(onlinePlayers, roomPlayers);
                    }
                } 
                else if (action == ActionType.AN_GANG || action == ActionType.BU_GANG) {
                    // 4. 主动杠牌逻辑 (暗杠/补杠)
                    System.out.println("【网络中枢】收到玩家 " + seatIndex + " 的主动杠牌请求...");
                    CardInfo targetCard = req.getCard();
                    
                    // 控制器内部已包含判断：若是暗杠则直接发牌；若是补杠则直接挂起并开启状态机
                    boolean success = gameController.processSelfKong(seatIndex, targetCard, onlinePlayers, roomPlayers);
                    
                    if (success) {
                        System.out.println("【网络中枢】杠牌流转执行完毕，生成最新桌面状态全服广播...");
                        broadcastGameState(); 
                    } else {
                        System.out.println("【网络中枢】异常：玩家 " + seatIndex + " 的主动杠牌数据校验未通过！");
                    }
                }
            } else {
                // ---------------- 场景 B：被动拦截回合 ----------------
                
                // 注意：在别人回合点的 HU（点炮、抢杠胡），必须和碰、吃、杠一起，统统交给状态机进行优胜劣汰！
                if (action == ActionType.HU || action == ActionType.PONG || action == ActionType.MING_GANG || action == ActionType.CHI || action == ActionType.PASS) {
                    System.out.println("【网络中枢】收到玩家 " + seatIndex + " 的动作拦截指令: " + action);

                    // 统一推入状态机缓存。
                    // 状态机运算完毕后，会自动回调 GameController，所以这里不需要接收 boolean 也不需要手动 broadcast
                    gameController.receiveInterceptAction(
                            seatIndex, 
                            action.getNumber(), 
                            req.getTotalFan(), 
                            req.getFanNamesList() == null ? new ArrayList<>() : req.getFanNamesList(),
                            req.getChiCardsList() == null ? new ArrayList<>() : req.getChiCardsList(),
                            onlinePlayers, 
                            roomPlayers
                    );
                }
            }
        });

        // --- 1009: 玩家确认结算，点击“准备下一局” ---
        handlers.put(1009, (ctx, msg) -> {
            String cid = ctx.channel().id().asLongText();
            Player p = onlinePlayers.get(cid);
            if (p == null) return;

            gameController.playerReadyForNextMatch(cid);
            System.out.println("【就绪】玩家 " + p.getNickname() + " 准备进入下一局");

            // 全员准备完毕
            if (gameController.isAllReadyForNextMatch(roomPlayers.size())) {
                gameController.clearReadyState();

                // 判定是否打满总庄数
                if (gameController.isGameSessionOver()) {
                    broadcastFinalVictory();
                } else {
                    System.out.println("【流程】开启新一局 (" + (gameController.getCurrentMatchCount() + 1) + ")");
                    gameController.startNewMatch(roomPlayers);
                    
                    Map<String, Player> currentRoomPlayers = new HashMap<>();
                    roomPlayers.forEach(id -> currentRoomPlayers.put(id, onlinePlayers.get(id)));
                    
                    // 获取 RoomManager 算出的最新庄家座位
                    int nextZhuang = 0;
                    if (gameController.getRoomManager() != null) {
                        nextZhuang = gameController.getRoomManager().getCurrentZhuangSeat();
                    }
                    
                    Dealer.getInitialDealData(gameController.getDeck(), currentRoomPlayers, nextZhuang);
                    broadcastGameState(); 
                }
            }
        });
    }

    /**
     * 广播桌面状态同步包 (1005)
     */
    public static void broadcastGameState() {
        MainMessage syncMsg = gameController.buildGameStateSync(roomPlayers);
        for (String cid : roomPlayers) {
            Player p = onlinePlayers.get(cid);
            if (p != null && p.getChannel().isActive()) {
                p.getChannel().writeAndFlush(syncMsg);
            }
        }
    }

    /**
     * 广播玩家列表 (1003)
     */
    public static void broadcastPlayerList() {
        PlayerList.Builder listBuilder = PlayerList.newBuilder();
        for (int i = 0; i < playerOrder.size(); i++) {
            String cid = playerOrder.get(i);
            Player p = onlinePlayers.get(cid);
            if (p != null) {
                listBuilder.addPlayers(PlayerInfo.newBuilder()
                        .setNickname(p.getNickname())
                        .setIsHost(i == 0)
                        .setSeatIndex(i)
                        .build());
            }
        }
        MainMessage msg = MainMessage.newBuilder().setCode(1003).setPlayerList(listBuilder.build()).build();
        onlinePlayers.values().forEach(p -> p.getChannel().writeAndFlush(msg));
    }

    /**
     * 广播单局结算小结 (1008) - 兜底方法
     */
    public static void broadcastRoundSummary(int winnerSeat, String winType) {
        RoundSummary summary = gameController.buildRoundSummary(winnerSeat, winType, roomPlayers);
        MainMessage msg = MainMessage.newBuilder()
                .setCode(1008)
                .setRoundSummary(summary) 
                .build();
        for (String cid : roomPlayers) {
            Player p = onlinePlayers.get(cid);
            if (p != null && p.getChannel().isActive()) {
                p.getChannel().writeAndFlush(msg);
            }
        }
    }

    /**
     * 执行整场大局终局广播 (1007)
     */
    public static void broadcastFinalVictory() {
        System.out.println("【终局】大局圈数已满，推送总榜单 (1007)");

        List<Player> sortedPlayers = roomPlayers.stream()
                .map(onlinePlayers::get)
                .filter(Objects::nonNull)
                .sorted((p1, p2) -> Integer.compare(p2.getScore(), p1.getScore()))
                .collect(Collectors.toList());

        if (sortedPlayers.isEmpty()) return;

        Player winner = sortedPlayers.get(0);

        FinalResult.Builder resultBuilder = FinalResult.newBuilder()
                .setWinnerNickname(winner.getNickname())
                .setWinningScore(winner.getScore())
                .setEndReason("打满总圈数结束");

        for (int i = 0; i < sortedPlayers.size(); i++) {
            Player p = sortedPlayers.get(i);
            resultBuilder.addLeaderBoard(PlayerFinalInfo.newBuilder()
                    .setNickname(p.getNickname())
                    .setTotalScore(p.getScore())
                    .setSeatIndex(p.getSeatIndex())
                    .setRank(i + 1)
                    .build());
        }

        MainMessage victoryMsg = MainMessage.newBuilder()
                .setCode(1007)
                .setFinalResult(resultBuilder.build())
                .build();

        roomPlayers.forEach(cid -> {
            Player p = onlinePlayers.get(cid);
            if (p != null && p.getChannel().isActive()) {
                p.getChannel().writeAndFlush(victoryMsg);
            }
        });
    }

    public static void removePlayer(String channelId) {
        onlinePlayers.remove(channelId);
        playerOrder.remove(channelId);
        broadcastPlayerList();
    }

    public static void dispatch(ChannelHandlerContext ctx, MainMessage msg) {
        CmdHandler handler = handlers.get(msg.getCode());
        if (handler != null) {
            handler.execute(ctx, msg);
        }
    }
}