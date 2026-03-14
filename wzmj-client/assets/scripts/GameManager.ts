import { _decorator, Component, Node, Prefab, instantiate, Label, director, find, log, Color, Button, ScrollView, Sprite, Layout, resources, SpriteFrame } from 'cc';
import { NetworkManager } from './NetworkManager';
import { CardUI } from './CardUI';
import { MainMessage, CardInfo, ActionType } from './proto/GameMessage'; 

const { ccclass, property } = _decorator;

@ccclass('GameManager')
export class GameManager extends Component {
    // --- UI 绑定 ---
    @property([Node]) seatNodes: Node[] = [];  // 展示各个玩家碰/杠/吃的【成牌区】
    @property(Node) handArea: Node = null!;    // 展示自己的手牌【手牌区】
    @property(Node) centerArea: Node = null!;  // 展示场上【所有历史弃牌】的 Layout 容器
    @property(Node) caishenDisplayNode: Node = null!; // 绑定 UI 上的展示位
    @property(Prefab) cardPrefab: Prefab = null!;
    
    @property(SpriteFrame) public tileBackFrame: SpriteFrame | null = null; // 麻将背面的图片
    @property(SpriteFrame) public tileFrontFrame: SpriteFrame | null = null; // 麻将正面的空白底图
    
    @property([Label]) nameLabels: Label[] = [];
    @property([Label]) scoreLabels: Label[] = [];
    @property([Label]) typeLabels: Label[] = []; 
    @property([Label]) zhuangLabels: Label[] = []; 
    
    @property(Prefab) resultPanelPrefab: Prefab = null;
    @property(Prefab) rankItemPrefab: Prefab = null;
    
    @property(Label) leftCardLabel: Label = null;  // 显示：剩余牌墙数量
    @property(Label) turnStatusLabel: Label = null; // 显示：当前是谁的回合
    @property(Label) gameCountLabel: Label = null; // 显示：局数显示组件

    @property(Button) btnChi: Button = null!;  // “吃”按钮
    @property(Button) btnPong: Button = null!; // “碰”按钮
    @property(Button) btnKong: Button = null!; // “杠”按钮
    @property(Button) btnHu: Button = null!;   // “胡”按钮（仅在有胡牌资格时显示）
    @property(Button) btnPass: Button = null!; // “过”按钮（放弃碰/杠/胡时使用）

    private netManager: NetworkManager | null = null;
    private myServerSeat: number = -1; 
    
    private selectedCardNode: Node | null = null; 
    private currentActionSeat: number = -1; 

    private myFormedSetsData: any[] = [];      // 记录副露组合
    private currentFanNames: string[] = [];
    private currentTotalFan: number = 0;

    private globalDiscardedHistory: any[] = [];

    private isAfterChiPong: boolean = false;   // 记录进入 3N+2 状态的原因。如果是吃/碰带来的，封锁自摸判定
    private globalMeldStructures: number[][] = []; // 记录每个玩家每个副露里的牌数
    private activeMeldHighlight: { seat: number, index: number } | null = null;
    private isFirstSync: boolean = true;

    private caishenInfo: CardInfo | null = null; // 财神信息

    // --- 贴图缓存 ---
    // 创建一个极其可靠的内存字典，用来存储所有贴图
    private tileCache: Map<string, SpriteFrame> = new Map();
    // 贴图加载状态锁
    private isAssetsLoaded: boolean = false; 
    // 用于暂存在加载期间到达的桌面状态包
    private pendingGameStateMsg: any = null;

    // 拦截阶段的防抖锁：防止在同一个拦截窗口内连发多次 PASS
    private isInterceptLockActive: boolean = false;

    // --- 交互控制状态机 ---
    // NORMAL: 正常摸打状态 ; CHI_SELECTION: 正在选择用于吃牌的手牌
    private interactionMode: 'NORMAL' | 'CHI_SELECTION' | 'KONG_SELECTION' = 'NORMAL';
    
    // 记录上一张全场被打出的牌 (用于吃牌校验)
    private currentChiTargetCard: any = null; 
    
    // 记录在吃牌模式下，当前被弹起的节点
    // private selectedChiNode: Node | null = null;
    
    // 暂存玩家当前真实的暗手牌数据列表 (你需要确保在 onReceiveGameStateSync 时把手牌存进这个变量)
    private myHandCardsData: any[] = [];

    onLoad() {
        const netNode = director.getScene().getChildByName("NetworkManager") || find("NetworkManager");
        if (netNode) {
            this.netManager = netNode.getComponent(NetworkManager);
            netNode.on("GameStateSync", this.onReceiveGameStateSync, this);
        }
        if (this.netManager) {
            this.myServerSeat = this.netManager.getMySeatIndex() === undefined ? 0 : this.netManager.getMySeatIndex();
            log(`【游戏】载入场景成功，我的座位号: ${this.myServerSeat}`);
        }
    }

    start() {
        this.loadAllMahjongTiles();

        log("【系统】GameManager 正在注册全局结算监听器...");
        director.on("FinalResult", this.onReceiveFinalResult, this);
        director.on("RoundSummary", this.onReceiveRoundSummary, this);
    }

    private loadAllMahjongTiles() {
        // 使用引擎内置的动态加载接口
        resources.loadDir("MahjongTiles", SpriteFrame, (err, assets) => {
            if (err) {
                console.error("【UI致命错误】加载麻将贴图文件夹失败！", err);
                return;
            }
            
            // 遍历加载出来的所有图片
            assets.forEach((frame) => {
                // frame.name 就是不带后缀的文件名，比如 "mj_1_1"
                this.tileCache.set(frame.name, frame);
            });
            
            console.log(`【UI系统】贴图全量加载完毕！共缓存了 ${this.tileCache.size} 张图片。`);
            this.isAssetsLoaded = true;
            
            if (this.pendingGameStateMsg) {
                console.log("【系统】贴图就绪，开始渲染暂存的桌面状态！");
                this.onReceiveGameStateSync(this.pendingGameStateMsg);
                this.pendingGameStateMsg = null; // 渲染完清空暂存器
            }
        });
    }

    /**
     * 牌节点穿透着色器
     * 无论贴图怎么覆盖，强制统一修改底板和花色的颜色
     */
    private setCardNodeTint(cardNode: Node, targetColor: Color) {
        const front = cardNode.getChildByName("Front");
        if (!front) return;

        // 1. 染底板
        const bgSprite = front.getComponent(Sprite);
        if (bgSprite) {
            bgSprite.color = targetColor;
        }

        // 2. 染花色
        const faceNode = front.getChildByName("Face");
        if (faceNode) {
            const faceSprite = faceNode.getComponent(Sprite);
            if (faceSprite) {
                faceSprite.color = targetColor;
            }
        }
    }

    // --- 核心渲染逻辑 ---

    private onReceiveGameStateSync(msg: MainMessage) {
        // 消息拦截与暂存
        if (!this.isAssetsLoaded) {
            this.pendingGameStateMsg = msg;
            console.log("【系统】贴图仍在加载中，已暂存最新的 1005 桌面同步包...");
            return; 
        }

        const data = msg.gameState;
        if (!data) return;

        // 【保留核心变量】：判断当前服务器是否挂起了一张目标牌
        const hasFloatingDiscard = data.lastDiscardedCard !== undefined && data.lastDiscardedCard !== null;

        // ==========================================
        // 【二维差分引擎】：精准夺取全场唯一高亮
        // ==========================================
        if (this.isFirstSync) {
            this.globalMeldStructures = new Array(data.players ? data.players.length : 4).fill([]);
        }

        if (data.players) {
            data.players.forEach((p: any) => {
                const sIndex = p.seatIndex === undefined ? 0 : p.seatIndex;
                const currentSets = p.fixedSets || [];
                // 映射出当前玩家副露的长度数组，例如: [3, 4, 3]
                const currentStructure = currentSets.map((set: any) => set.cards ? set.cards.length : 0);
                
                if (!this.isFirstSync) {
                    const oldStructure = this.globalMeldStructures[sIndex] || [];
                    let diffIndex = -1;

                    if (currentStructure.length > oldStructure.length) {
                        // 算法 A：副露数量增加 (吃、碰、明杠、暗杠)
                        diffIndex = currentStructure.length - 1;
                    } else if (currentStructure.length === oldStructure.length) {
                        // 算法 B：副露数量没变，但某个副露变长了 (补杠)
                        for (let i = 0; i < currentStructure.length; i++) {
                            if (currentStructure[i] > oldStructure[i]) {
                                diffIndex = i;
                                break;
                            }
                        }
                    } else {
                        // 算法 C：新开局清空
                        this.activeMeldHighlight = null;
                    }

                    // 捕捉到动作，指针指向该副露
                    if (diffIndex !== -1) {
                        this.activeMeldHighlight = { seat: sIndex, index: diffIndex };
                    }
                }
                this.globalMeldStructures[sIndex] = currentStructure;
            });
        }

        // 【新增互斥法则】：如果发现有人打出了新牌 (牌河变长)，立刻强制熄灭所有副露的高亮！
        const currentDiscardCount = data.globalDiscardedCards ? data.globalDiscardedCards.length : 0;
        const oldDiscardCount = this.globalDiscardedHistory ? this.globalDiscardedHistory.length : 0;
        if (currentDiscardCount > oldDiscardCount) {
            this.activeMeldHighlight = null;
        }
        
        this.isFirstSync = false;

        // ------------------------------------------
        // 基础 UI 渲染 (财神、庄家、圈数等)
        // ------------------------------------------
        if (data.caishenCard) {
            this.caishenInfo = data.caishenCard;
            if (this.caishenDisplayNode) {
                this.updateCardUI(this.caishenDisplayNode, true, data.caishenCard, false, true);
                this.setCardNodeTint(this.caishenDisplayNode, new Color(160, 230, 255));
            }
        }

        const zSeat = data.zhuangSeat === undefined ? 0 : data.zhuangSeat;       
        const zCount = data.zhuangGameCount === undefined ? 1 : data.zhuangGameCount;  
        const realPlayerCount = data.players ? data.players.length : 0; 

        this.zhuangLabels.forEach(l => l.node.active = false);

        for (let serverIdx = 0; serverIdx < realPlayerCount; serverIdx++) {
            const localIdx = this.getLocalSeatIndex(serverIdx, realPlayerCount);
            const targetLabel = this.zhuangLabels[localIdx];
            
            if (targetLabel) {
                targetLabel.node.active = true;
                if (serverIdx === zSeat) {
                    targetLabel.string = `第 ${zCount} 庄`;
                    targetLabel.color = new Color(255, 50, 50); 
                } else {
                    targetLabel.string = "闲家";
                    targetLabel.color = new Color(255, 255, 255);
                }
            }
        }

        if (this.gameCountLabel) {
            const dealerIndex = data.currentMatchCount;
            this.gameCountLabel.string = `第 ${dealerIndex} / ${zCount} 庄`;
        }

        this.currentActionSeat = data.currentActionSeat === undefined ? 0 : data.currentActionSeat;
        const totalPlayers = data.players.length;

        if (this.myServerSeat === -1 && this.netManager) {
            this.myServerSeat = this.netManager.getMySeatIndex();
        }

        if (this.leftCardLabel) {
            const remain = data.remainingCardsCount === undefined ? 0 : data.remainingCardsCount;
            this.leftCardLabel.string = `余 ${remain} 张`;
        }

        this.interactionMode = 'NORMAL'; 

        const isMyTurn = (this.currentActionSeat === this.myServerSeat);
        if (this.turnStatusLabel) {
            if (!isMyTurn) {
                this.turnStatusLabel.string = "回合外";
                this.turnStatusLabel.color = new Color(255, 255, 255);
            } else {
                this.turnStatusLabel.string = "请出牌";
                this.turnStatusLabel.color = new Color(255, 255, 255);
            }
        }
        
        if (!isMyTurn) {
            this.isAfterChiPong = false;
        }

        this.resetActionButtons();
        this.clearPersonalTable();

        // 拦截锁重置逻辑
        let isAnyPlayerActive = false;
        const playersList = data.players || [];
        for (let p of playersList) {
            if (p.handCards && p.handCards.length % 3 === 2) {
                isAnyPlayerActive = true;
                break;
            }
        }

        if (isAnyPlayerActive) {
            this.isInterceptLockActive = false;
        }

        // ------------------------------------------
        // 遍历玩家进行渲染
        // ------------------------------------------
        data.players.forEach((player: any) => {
            const sIndex = player.seatIndex === undefined ? 0 : player.seatIndex; 
            const isMe = (sIndex === this.myServerSeat);
            const logicalIndex = this.getLocalSeatIndex(sIndex, totalPlayers);

            // 信息面板
            if (this.nameLabels[logicalIndex]) {
                this.nameLabels[logicalIndex].string = player.nickname || "未知玩家";
                this.nameLabels[logicalIndex].node.active = true;
            }
            if (this.scoreLabels[logicalIndex]) {
                const currentScore = player.score === undefined ? 0 : player.score; 
                this.scoreLabels[logicalIndex].string = `${currentScore} 分`;
                this.scoreLabels[logicalIndex].color = new Color(184, 134, 11); 
            }
            if (this.typeLabels[logicalIndex]) {
                const label = this.typeLabels[logicalIndex];
                if (sIndex === this.currentActionSeat) {
                    label.string = "思考中...";
                    label.color = new Color(0, 255, 0);
                    label.node.active = true;
                } else {
                    label.node.active = false;
                }
            }

            // ==========================================
            // 渲染成牌区 (碰/杠/吃)
            // ==========================================
            const seatNode = this.seatNodes[logicalIndex];
            if (seatNode) {
                seatNode.removeAllChildren(); 
                
                const fixedSets = player.fixedSets || [];
                fixedSets.forEach((cardSet: any, setIndex: number) => {
                    const cards = cardSet.cards ? [...cardSet.cards] : [];
                    
                    cards.sort((a, b) => {
                        const typeA = a.type === undefined ? 0 : a.type;
                        const typeB = b.type === undefined ? 0 : b.type;
                        if (typeA !== typeB) return typeA - typeB; 
                        const valA = a.value === undefined ? 0 : a.value;
                        const valB = b.value === undefined ? 0 : b.value;
                        return valA - valB; 
                    });

                    const setContainerNode = new Node("CardSetContainer");
                    const setLayout = setContainerNode.addComponent(Layout);
                    setLayout.type = Layout.Type.HORIZONTAL;
                    setLayout.resizeMode = Layout.ResizeMode.CONTAINER; 
                    setLayout.spacingX = 5; 
                    seatNode.addChild(setContainerNode);

                    const actionType = cardSet.type;
                    const targetCard = cardSet.targetCard;
                    let hasHighlightedTarget = false; 

                    // 【核心互斥研判】：绝对匹配差分指针
                    const isThisMeldHighlighted = this.activeMeldHighlight !== null && 
                                                  this.activeMeldHighlight.seat === sIndex && 
                                                  this.activeMeldHighlight.index === setIndex;

                    cards.forEach((cardData, cardIdx) => {
                        const cardNode = instantiate(this.cardPrefab);
                        setContainerNode.addChild(cardNode);
                        
                        cardData.type = cardData.type === undefined ? 0 : cardData.type;
                        cardData.value = cardData.value === undefined ? 0 : cardData.value;
                        
                        let isHighlight = false;
                        if (isThisMeldHighlighted) { 
                            if (actionType === ActionType.AN_GANG) {
                                isHighlight = true; 
                            } else if (actionType === ActionType.BU_GANG) {
                                if (cardIdx === 3) isHighlight = true; 
                            } else {
                                if (targetCard && !hasHighlightedTarget && 
                                    cardData.type === targetCard.type && cardData.value === targetCard.value) {
                                    isHighlight = true;
                                    hasHighlightedTarget = true;
                                }
                            }
                        }

                        this.updateCardUI(cardNode, true, cardData, isHighlight); 
                    });
                });
            }

            // ==========================================
            // 渲染我的手牌区
            // ==========================================
            if (isMe) {
                this.myHandCardsData = player.handCards || [];
                this.myFormedSetsData = player.fixedSets || [];

                if (this.handArea) {
                    this.handArea.removeAllChildren(); 
                }

                const handCards = [...this.myHandCardsData]; 
                let sortedCards: any[] = [];
                let newDrawnCard: any = null;
                let isRightmostHighlight = false; 

                if (handCards.length % 3 === 2) {
                    if (this.isAfterChiPong) {
                        sortedCards = [...handCards];
                        isRightmostHighlight = true;
                    } else {
                        newDrawnCard = handCards.pop(); 
                        sortedCards = [...handCards];          
                    }
                } else {
                    sortedCards = [...handCards];
                }

                sortedCards.sort((a, b) => {
                    const isAJoker = (a.type === 4 && a.value === 5);
                    const isBJoker = (b.type === 4 && b.value === 5);

                    if (isAJoker && !isBJoker) return -1; 
                    if (!isAJoker && isBJoker) return 1;

                    const typeA = a.type === undefined ? 0 : a.type;
                    const typeB = b.type === undefined ? 0 : b.type;
                    if (typeA !== typeB) return typeA - typeB; 
                    const valA = a.value === undefined ? 0 : a.value;
                    const valB = b.value === undefined ? 0 : b.value;
                    return valA - valB; 
                });

                const realHandCountUI = this.myHandCardsData.length;
                const isMyTurnActive = (this.currentActionSeat === this.myServerSeat && realHandCountUI % 3 === 2);

                sortedCards.forEach((cardData, index) => {
                    const cardNode = instantiate(this.cardPrefab);
                    this.handArea.addChild(cardNode);
                    cardNode.on(Node.EventType.TOUCH_END, this.onHandCardClick, this);
                    
                    let isHighlight = false;
                    if (isRightmostHighlight && index === sortedCards.length - 1) {
                        isHighlight = true;
                    }

                    const isRestricted = isMyTurnActive ? !this.getPlayableStatus(cardData.type, cardData.value) : false;
                    
                    this.updateCardUI(cardNode, true, cardData, isHighlight, false, isRestricted); 
                });

                if (newDrawnCard) {
                    const cardNode = instantiate(this.cardPrefab);
                    this.handArea.addChild(cardNode);
                    cardNode.on(Node.EventType.TOUCH_END, this.onHandCardClick, this);
                    
                    const isRestricted = isMyTurnActive ? !this.getPlayableStatus(newDrawnCard.type, newDrawnCard.value) : false;
                    this.updateCardUI(cardNode, true, newDrawnCard, true, false, isRestricted); 
                }

                // ==========================================
                // 动作拦截与通道判定
                // ==========================================
                const lastDiscard = data.lastDiscardedCard; 
                this.currentChiTargetCard = lastDiscard;
                const remain = data.remainingCardsCount === undefined ? 0 : data.remainingCardsCount;
                const realHandCount = this.myHandCardsData.length;

                // 场景 A：我的回合 (自摸、暗杠、补杠)
                if (this.currentActionSeat === this.myServerSeat && realHandCount % 3 === 2) {
                    if (!this.isAfterChiPong) {
                        log("【系统】我的回合，执行自摸与主动判定...");
                        
                        const huResult = this.checkCanHu(this.myHandCardsData, null, this.myFormedSetsData); 
                        if (huResult && huResult.canHu) { 
                            this.currentTotalFan = huResult.totalFan === undefined ? 0 : huResult.totalFan;
                            this.currentFanNames = huResult.fanNames || [];
                            
                            this.setActionButtonState(this.btnHu, true, 250, 33, 33); 
                            this.setActionButtonState(this.btnPass, true, 255, 255, 255);
                        }

                        if (remain > 0 && this.checkCanAnOrBuKong(this.myHandCardsData, this.myFormedSetsData)) {
                            this.setActionButtonState(this.btnKong, true, 252, 222, 69); 
                            this.setActionButtonState(this.btnPass, true, 255, 255, 255);
                        }
                    }
                }
                
                // 场景 B：他人的回合 (点炮、明杠、碰、吃)
                else if (this.currentActionSeat !== this.myServerSeat && realHandCount % 3 === 1 && lastDiscard && !isAnyPlayerActive) {
                    log("【系统】他人回合，执行外部拦截判定...");
                    let hasAnyAction = false;
                    
                    // 1. 胡牌(点炮/抢杠)无视任何物理通道封锁，绝对优先检测
                    const huResult = this.checkCanHu(this.myHandCardsData, lastDiscard, this.myFormedSetsData); 
                    if (huResult && huResult.canHu) { 
                        this.currentTotalFan = huResult.totalFan === undefined ? 0 : huResult.totalFan;
                        this.currentFanNames = huResult.fanNames || [];
                        
                        this.setActionButtonState(this.btnHu, true, 250, 33, 33); 
                        this.setActionButtonState(this.btnPass, true, 255, 255, 255);
                        hasAnyAction = true;
                    }

                    // 【核心安全防线】：利用 hasFloatingDiscard 判定当前是否处于抢杠胡挂起期！
                    const isQiangGangWindow = this.activeMeldHighlight !== null && 
                                              this.activeMeldHighlight.seat === this.currentActionSeat && 
                                              hasFloatingDiscard;

                    if (remain > 0 && !isQiangGangWindow) { 
                        // 常规拦截通道
                        if (this.checkCanMingKong(this.myHandCardsData, lastDiscard)) {
                            this.setActionButtonState(this.btnKong, true, 252, 222, 69);
                            this.setActionButtonState(this.btnPass, true, 255, 255, 255);
                            hasAnyAction = true;
                        }
                        if (this.checkCanPong(this.myHandCardsData, lastDiscard)) {
                            this.setActionButtonState(this.btnPong, true, 36, 141, 255); 
                            this.setActionButtonState(this.btnPass, true, 255, 255, 255);
                            hasAnyAction = true;
                        }
                        const isShangJia = (this.myServerSeat - 1 + totalPlayers) % totalPlayers === this.currentActionSeat;
                        if (isShangJia && this.checkCanChi(this.myHandCardsData, lastDiscard)) {
                            this.setActionButtonState(this.btnChi, true, 124, 36, 255); 
                            this.setActionButtonState(this.btnPass, true, 255, 255, 255);
                            hasAnyAction = true;
                        }
                    } else if (isQiangGangWindow) {
                        log("【系统】当前处于抢杠胡绝对挂起期，封锁物理吃/碰/杠！");
                    }

                    if (!hasAnyAction) {
                        if (!this.isInterceptLockActive) {
                            this.isInterceptLockActive = true; 
                            if (this.netManager) {
                                this.netManager.sendPlayerAction(ActionType.PASS);
                            }
                        }
                    }
                }
            }
        });

        // ------------------------------------------
        // 渲染牌河与全局高亮调用
        // ------------------------------------------
        // 此时将本次历史数据覆盖到本地，用于下一次比较
        this.globalDiscardedHistory = data.globalDiscardedCards || [];
        
        const currentUINodesCount = this.centerArea ? this.centerArea.children.length : 0;
        if (this.globalDiscardedHistory.length === 0 && this.centerArea) {
            this.centerArea.removeAllChildren();
        } else if (this.globalDiscardedHistory.length > currentUINodesCount) {
            for (let i = currentUINodesCount; i < this.globalDiscardedHistory.length; i++) {
                this.appendDiscardedCard(this.globalDiscardedHistory[i]);
            }
        } else if (this.globalDiscardedHistory.length < currentUINodesCount) {
            if (this.centerArea) this.centerArea.removeAllChildren();
            this.globalDiscardedHistory.forEach((card: any) => this.appendDiscardedCard(card));
        }

        // 调用互斥的牌河高亮渲染
        this.highlightLastDiscard();
    }

    /**
     * 刷新牌河高亮：实现了与副露高亮的绝对互斥
     */
    private highlightLastDiscard() {
        if (!this.centerArea || this.centerArea.children.length === 0) return;

        // 互斥法则：如果全局差分算法显示有任何副露正在发光，牌河绝对不准发光！
        const canHighlight = (this.activeMeldHighlight === null); 

        const children = this.centerArea.children;
        children.forEach((cardNode, index) => {
            const isLast = (index === children.length - 1);
            
            if (isLast && canHighlight) {
                this.setCardNodeTint(cardNode, new Color(255, 255, 150));
            } else {
                this.setCardNodeTint(cardNode, Color.WHITE);
            }
        });
    }

    /**
     * 获取某张牌当前是否允许被打出
     * @returns true: 可以打出 / false: 被风牌规则锁定，禁止打出
     */
    private getPlayableStatus(cardType: number, cardValue: number): boolean {
        // 1. 真财神（白板）在任何情况下都绝对禁止打出
        if (cardType === 4 && cardValue === 5) return false;

        // 2. 统计手牌中的风牌数量
        const windCounts = new Map<number, number>();
        for (const c of this.myHandCardsData) {
            const cType = c.type === undefined ? 0 : c.type;
            const cVal = c.value === undefined ? 0 : c.value;
            if (cType === 4 && cVal >= 1 && cVal <= 4) {
                windCounts.set(cVal, (windCounts.get(cVal) || 0) + 1);
            }
        }

        // 3. 触发检测：寻找是否有数量为 1 的“单张风”
        let hasSingleWind = false;
        for (const count of windCounts.values()) {
            if (count === 1) {
                hasSingleWind = true;
                break;
            }
        }

        // 如果没有单张风，规则不触发，任何非财神的牌都可以打
        if (!hasSingleWind) return true;

        // 4. 交集检测：寻找牌河里的风
        const discardWinds = new Set<number>();
        for (const c of this.globalDiscardedHistory) {
            const cType = c.type === undefined ? 0 : c.type;
            const cVal = c.value === undefined ? 0 : c.value;
            if (cType === 4 && cVal >= 1 && cVal <= 4) {
                discardWinds.add(cVal);
            }
        }

        const intersection = new Set<number>();
        for (const windVal of windCounts.keys()) {
            if (discardWinds.has(windVal)) {
                intersection.add(windVal);
            }
        }

        // 5. 判决输出
        // 如果想打的这张牌是风牌
        if (cardType === 4 && cardValue >= 1 && cardValue <= 4) {
            if (intersection.size > 0) {
                // 有交集：必须打交集里的风（跟风）
                return intersection.has(cardValue);
            } else {
                // 无交集：手里的任何风都可以打
                return true;
            }
        } else {
            // 如果想打的不是风牌，直接禁止
            return false;
        }
    }

    /**
     * 检查是否可以暗杠或补杠 (自己回合内调用)
     * @param handCards 玩家当前的暗手牌数组
     * @param formedSets 玩家已经摆在桌面的组合数组
     * @returns boolean
     */
    private checkCanAnOrBuKong(handCards: any[], formedSets: any[]): boolean {
        // 安全拦截：防范空指针与无效数组
        if (!handCards || handCards.length === 0) return false;

        // 1. 数据收集阶段：使用 Map 统计手中每张牌的数量
        // Key 格式为 "type_value" (例如万子3就是 "1_3")，Value 为数量
        const cardCountMap = new Map<string, number>();

        for (let c of handCards) {
            const cType = c.type === undefined ? 0 : c.type;
            const cVal = c.value === undefined ? 0 : c.value;
            const key = `${cType}_${cVal}`;
            
            const currentCount = cardCountMap.get(key) || 0;
            cardCountMap.set(key, currentCount + 1);
        }

        // 2. 逻辑判定阶段：遍历统计好的字典
        for (let [key, count] of cardCountMap.entries()) {
            
            // 场景 A：暗杠判定
            // 只要某种牌在手里达到了 4 张，立刻触发短路返回
            if (count === 4) {
                return true;
            }

            // 场景 B：补杠判定
            // 如果手中有这张牌 (count >= 1)，我们需要去副露区寻找是否碰过它
            if (count >= 1 && formedSets && formedSets.length > 0) {
                // 解析出当前这张牌的真实 type 和 value
                const parts = key.split('_');
                const cType = parseInt(parts[0], 10);
                const cVal = parseInt(parts[1], 10);

                for (let set of formedSets) {
                    if (!set.cards || set.cards.length === 0) continue;
                    const setType = set.type === undefined ? ActionType.DRAW : set.type;
                    
                    if (setType === ActionType.PONG && set.cards && set.cards.length > 0) {
                        const setCardType = set.cards[0].type === undefined ? 0 : set.cards[0].type;
                        const setCardVal = set.cards[0].value === undefined ? 0 : set.cards[0].value;
                        
                        // 碰牌的花色和数值与手中的这张牌完全一致
                        if (setCardType === cType && setCardVal === cVal) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * 检查是否可以明杠 (别人打出牌时调用)
     * @param handCards 玩家当前的暗手牌数组
     * @param targetCard 别人刚打出的那张牌
     * @returns boolean
     */
    private checkCanMingKong(handCards: any[], targetCard: any): boolean {
        if (!handCards || !targetCard) return false;

        const targetType = targetCard.type === undefined ? 0 : targetCard.type;
        const targetVal = targetCard.value === undefined ? 0 : targetCard.value;
        let matchCount = 0;

        // 遍历暗手牌寻找完全匹配的牌
        for (let c of handCards) {
            const cType = c.type === undefined ? 0 : c.type;
            const cVal = c.value === undefined ? 0 : c.value;
            
            if (cType === targetType && cVal === targetVal) {
                if (++matchCount === 3) {
                    return true;
                }
            }
        }
        
        // 如果遍历完整个手牌，matchCount 仍未达到 3，则判定失败
        return false;
    }

    /**
     * 检查是否可以碰牌 (别人打出牌时调用)
     * @param handCards 玩家当前的暗手牌数组
     * @param targetCard 别人刚打出的那张牌
     * @returns boolean
     */
    private checkCanPong(handCards: any[], targetCard: any): boolean {
        if (!handCards || !targetCard) return false;

        const targetType = targetCard.type === undefined ? 0 : targetCard.type;
        const targetVal = targetCard.value === undefined ? 0 : targetCard.value;
        let matchCount = 0;

        if (targetType === 4 && targetVal === 5) return false; // 财神牌不能碰

        // 遍历暗手牌寻找完全匹配的牌
        for (let c of handCards) {
            const cType = c.type === undefined ? 0 : c.type;
            const cVal = c.value === undefined ? 0 : c.value;
            
            if (cType === targetType && cVal === targetVal) {
                if (++matchCount === 2) {
                    return true;
                }
            }
        }
        
        // 如果遍历完整个手牌，matchCount 仍未达到 2，则判定失败
        return false;
    }

    /**
     * 检查是否可以吃牌 (上家打出牌时调用)
     * @param handCards 玩家当前的暗手牌数组
     * @param targetCard 上家刚打出的那张牌
     * @returns boolean
     */
    private checkCanChi(handCards: any[], targetCard: any): boolean {
        // 安全拦截：如果手牌为空或目标牌不存在，直接返回 false
        if (!handCards || !targetCard) return false;

        const tType = targetCard.type === undefined ? 0 : targetCard.type;
        const tVal = targetCard.value === undefined ? 0 : targetCard.value;

        // 牌型为字牌或财神的牌，无法进行吃牌操作
        if (tType === 4) return false;

        // 1. 使用 Set 提取并去重同花色的牌值
        const availableVals = new Set<number>();

        for (let c of handCards) {
            const cType = c.type === undefined ? 0 : c.type;
            const cVal = c.value === undefined ? 0 : c.value;
            
            // 只有同花色的牌才有资格参与“吃”的判定
            if (cType === tType) {
                availableVals.add(cVal);
            }
        }

        // 2. 利用 Set 的 O(1) 查询效率进行验证
        // 只要下面三种组合中的任意一种成立，立刻短路返回 true

        // 组合 1：存在 [目标-2] 和 [目标-1]
        if (availableVals.has(tVal - 2) && availableVals.has(tVal - 1)) {
            return true;
        }

        // 组合 2：存在 [目标-1] 和 [目标+1]
        if (availableVals.has(tVal - 1) && availableVals.has(tVal + 1)) {
            return true;
        }

        // 组合 3：存在 [目标+1] 和 [目标+2]
        if (availableVals.has(tVal + 1) && availableVals.has(tVal + 2)) {
            return true;
        }

        return false;
    }

    /**
     * 追加单张弃牌到公共牌池 Layout
     */
    private appendDiscardedCard(cardData: CardInfo) {
        if (!this.centerArea) return;
        const cardNode = instantiate(this.cardPrefab);
        this.centerArea.addChild(cardNode);
        this.updateCardUI(cardNode, true, cardData);
    }

    // --- 交互与操作逻辑 ---

    private onHandCardClick(event: any) {
        const cardNode = event.target as Node;
        let cardUI = cardNode.getComponent(CardUI) || cardNode.parent?.getComponent(CardUI);

        if (cardUI && cardUI.node.parent === this.handArea) {
            // 拦截吃牌选择
            if (this.interactionMode === 'CHI_SELECTION') {
                this.handleChiCardSelection(cardUI);
                return; // 拦截成功，绝对不执行下方的正常出牌逻辑
            }

            // 拦截杠牌选择
            if (this.interactionMode === 'KONG_SELECTION') {
                this.handleKongCardSelection(cardUI);
                return;
            }

            // 正常出牌逻辑
            // --- 核心逻辑 1：二次点击确认打出 ---
            if (this.selectedCardNode === cardUI.node && cardUI.isSelected) {
                
                if (this.currentActionSeat !== this.myServerSeat) {
                    log("提示：还没轮到你出牌！");
                    cardUI.resetState();
                    this.selectedCardNode = null;
                    return;
                }

                if (this.handArea.children.length % 3 !== 2) {
                    log(`提示：当前手牌数为 ${this.handArea.children.length}，不符合出牌状态！`);
                    cardUI.resetState();
                    this.selectedCardNode = null;
                    return;
                }

                const isPlayable = this.getPlayableStatus(cardUI.type, cardUI.value);
                if (!isPlayable) {
                    log("规则限制，此牌当前不可出！");
                    return; 
                }

                if (this.netManager) {
                    const cardInfo = { type: cardUI.type, value: cardUI.value }; 
                    log(`【动作】打出手牌: ${this.getMahjongCardStr(cardInfo.type, cardInfo.value)}`);
                    
                    if (this.netManager) {
                        this.netManager.sendPlayerAction(ActionType.DISCARD, cardInfo);
                    }
                    this.selectedCardNode = null;
                    this.isAfterChiPong = false;

                    this.resetActionButtons();
                }
                return;
            }

            // --- 核心逻辑 2：唯一选中弹起 ---
            log(`【交互】选中手牌: ${this.getMahjongCardStr(cardUI.type, cardUI.value)}`);
            
            if (this.selectedCardNode && this.selectedCardNode !== cardUI.node) {
                this.selectedCardNode.getComponent(CardUI)?.resetState();
            }

            if (!cardUI.isSelected) {
                cardUI.toggleSelect(); 
            }
            this.selectedCardNode = cardUI.node;
        }
    }

    /**
     * 处理吃牌模式下的二次点击与算数校验
     */
    private handleChiCardSelection(cardUI: any) {
        // 1. 如果点的是另一张牌，把之前弹起的牌放下去
        if (this.selectedCardNode && this.selectedCardNode !== cardUI.node) {
            this.selectedCardNode.getComponent(CardUI)?.resetState();
            this.selectedCardNode = null;
        }

        // 2. 第一次点击：弹起
        if (!cardUI.isSelected) {
            cardUI.toggleSelect();
            this.selectedCardNode = cardUI.node;
            return;
        }

        // 3. 第二次点击同一张牌：确认吃牌！执行严密的算数校验
        const D = this.currentChiTargetCard; 
        if (!D) {
            log("【系统-Error】吃牌确认失败：找不到 currentChiTargetCard，请检查数据同步！");
            this.cancelSelectionMode();
            return;
        }
        
        // 严防 undefined
        const dType = D.type === undefined ? 0 : D.type;
        const dVal = D.value === undefined ? 0 : D.value;
        const sType = cardUI.type === undefined ? 0 : cardUI.type;
        const sVal = cardUI.value === undefined ? 0 : cardUI.value;

        // 花色校验
        if (dType !== sType) {
            log("【系统】吃牌失败：必须使用同花色的牌！");
            this.cancelSelectionMode();
            return;
        }

        // 算数分发逻辑 (找第二张牌)
        let requiredSecondValue = -1;
        
        if (sVal === dVal - 2) {
            requiredSecondValue = dVal - 1; // 选 2，目标 4，找 3
        } else if (sVal === dVal - 1) {
            requiredSecondValue = dVal + 1; // 选 3，目标 4，找 5
        } else if (sVal === dVal + 1) {
            requiredSecondValue = dVal + 2; // 选 5，目标 4，找 6
        } else {
            log("【系统】吃牌失败：该牌无法作为手牌中能吃的最小牌！");
            this.cancelSelectionMode();
            return;
        }

        // 遍历真实手牌数据，检查存不存在这第二张牌
        let hasSecondCard = false;
        for (let c of this.myHandCardsData) {
            const cType = c.type === undefined ? 0 : c.type;
            const cVal = c.value === undefined ? 0 : c.value;
            if (cType === sType && cVal === requiredSecondValue) {
                hasSecondCard = true;
                break;
            }
        }
        
        if (hasSecondCard) {
            log(`【系统】验证通过！使用 ${sVal} 和 ${requiredSecondValue} 组合吃牌！`);
            
            const actionData = {
                chiCards: [
                    { type: sType, value: sVal },
                    { type: sType, value: requiredSecondValue }
                ]
            };
            
            if (this.netManager) {
                this.isAfterChiPong = true;
                this.netManager.sendPlayerAction(ActionType.CHI, actionData);
            }
            
            this.resetActionButtons();
            this.cancelSelectionMode(); 
            
        } else {
            log(`【系统】吃牌失败：手里缺少配对的牌 ${requiredSecondValue}！`);
            this.cancelSelectionMode();
        }
    }

    /**
     * 处理杠牌模式下的二次点击与算数校验
     */
    private handleKongCardSelection(cardUI: any) {
        // 1. 切换目标时，重置旧的弹起状态
        if (this.selectedCardNode && this.selectedCardNode !== cardUI.node) {
            this.selectedCardNode.getComponent(CardUI)?.resetState();
            this.selectedCardNode = null;
        }

        // 2. 第一次点击：弹起
        if (!cardUI.isSelected) {
            cardUI.toggleSelect();
            this.selectedCardNode = cardUI.node;
            return;
        }

        // 3. 第二次点击同一张牌：确认杠牌！
        const targetType = cardUI.type === undefined ? 0 : cardUI.type;
        const targetVal = cardUI.value === undefined ? 0 : cardUI.value;

        // 情况 A：是否构成暗杠（手牌中有 4 张同样的牌）
        let handMatchCount = 0;
        for (let c of this.myHandCardsData) {
            const cType = c.type === undefined ? 0 : c.type;
            const cVal = c.value === undefined ? 0 : c.value;
            if (cType === targetType && cVal === targetVal) {
                handMatchCount++;
            }
        }

        let isAnGang = false;
        let isBuGang = false;
        let kongTypeStr = "";

        if (handMatchCount === 4) {
            isAnGang = true;
            kongTypeStr = "暗杠";
        } else {
            // 情况 B：是否构成补杠（手牌中有 1 张，且副露区有对应的碰牌）
            if (handMatchCount >= 1 && this.myFormedSetsData) {
                for (let set of this.myFormedSetsData) {
                    // 检查碰的牌是不是目标牌
                    if (set.type === ActionType.PONG && set.cards && set.cards.length > 0) {
                        const setCardType = set.cards[0].type === undefined ? 0 : set.cards[0].type;
                        const setCardVal = set.cards[0].value === undefined ? 0 : set.cards[0].value;
                        if (setCardType === targetType && setCardVal === targetVal) {
                            isBuGang = true;
                            kongTypeStr = "补杠";
                            break;
                        }
                    }
                }
            }
        }

        // 4. 判决执行
        if (isAnGang || isBuGang) {
            log(`【系统】审查通过！执行${kongTypeStr}，牌型: ${targetType}+${targetVal}`);
            
            // 直接平铺传参即可
            const actionData = {
                type: targetType, 
                value: targetVal
            };
            
            if (this.netManager) {
                if (isAnGang) {
                    this.netManager.sendPlayerAction(ActionType.AN_GANG, actionData);
                } else if (isBuGang) {
                    this.netManager.sendPlayerAction(ActionType.BU_GANG, actionData);
                }
            }
            
            this.resetActionButtons();
            this.cancelSelectionMode(); // 通用的取消模式函数
            
        } else {
            // 审查不通过：不符合暗杠或补杠条件，撤销操作，打回原位
            log(`【系统】违规操作：手牌 ${targetVal} 不符合暗杠或补杠条件！`);
            this.cancelSelectionMode();
        }
    }

    /**
     * 统一的取消选择状态
     */
    private cancelSelectionMode() {
        this.interactionMode = 'NORMAL';
        const isMyTurn = (this.currentActionSeat === this.myServerSeat);
        if (this.turnStatusLabel) {
            if (!isMyTurn) {
                this.turnStatusLabel.string = "回合外";
                this.turnStatusLabel.color = new Color(255, 255, 255);
            } else {
                this.turnStatusLabel.string = "请出牌";
                this.turnStatusLabel.color = new Color(255, 255, 255);
            }
        }
        if (this.selectedCardNode) {
            this.selectedCardNode.getComponent(CardUI)?.resetState();
            this.selectedCardNode = null;
        }
    }

    /** 绑定到“吃”按钮 (不发送网络请求，而是切换 UI 状态) */
    public onBtnAction_Chi() {
        log("【交互】进入吃牌模式！请点击手牌中用于吃牌最小的牌...");
        this.interactionMode = 'CHI_SELECTION';
        if (this.turnStatusLabel) {
            this.turnStatusLabel.string = "吃牌（选最小）";
            this.turnStatusLabel.color = new Color(124, 36, 255); // 和吃按钮同色的提示
        }
    }
    
    /** 绑定到“碰”按钮 */
    public onBtnAction_Pong() {
        if (this.netManager) {
            log("【动作】发送碰牌指令...");

            this.isAfterChiPong = true;
            this.netManager.sendPlayerAction(ActionType.PONG);
            this.resetActionButtons();
            this.cancelSelectionMode(); // 防御性清理
        }
    }

    /** 绑定到“杠”按钮 */
    public onBtnAction_Kong() {
        if (this.currentActionSeat !== this.myServerSeat) {
            // 场景 1：非自己回合，绝对是明杠，直接向服务器提交动作申请
            if (this.netManager) {
                log("【动作】发送明杠指令...");
                this.netManager.sendPlayerAction(ActionType.MING_GANG);
                this.resetActionButtons();
            }
        } else {
            // 场景 2：自己回合，可能是暗杠或补杠，进入“证据审查”交互模式
            this.interactionMode = 'KONG_SELECTION';
            if (this.turnStatusLabel) {
                this.turnStatusLabel.string = "补杠/暗杠";
                this.turnStatusLabel.color = new Color(252, 222, 69); // 和杠按钮同色的提示
            }
            log("【交互】进入杠牌模式！请点击手牌中需要杠的牌...");
        }
    }

    // “胡”按钮的核心逻辑：发送胡牌指令
    public onBtnAction_Hu() {
        if (this.netManager) {
            log(`【动作】发送胡牌指令！番数: ${this.currentTotalFan}`);
            
            // 构建带番型数据的请求载荷
            const actionData = {
                action: ActionType.HU,
                totalFan: this.currentTotalFan === undefined ? 0 : this.currentTotalFan,
                fanNames: this.currentFanNames || []
            };
            
            // 发送给服务器
            this.netManager.sendPlayerAction(ActionType.HU, actionData);
            
            // 发送后立刻置灰，防误触
            this.resetActionButtons(); 
        }
    }

    // “过”按钮的核心逻辑：发送过牌指令（如果当前有碰/杠/胡资格），或者仅重置 UI 状态（如果处于吃牌选择模式）
    public onBtnAction_Pass() {
        if (this.netManager) {
            log("【动作】发送过牌指令...");
            this.interactionMode = 'NORMAL'; // 无论如何先切回正常模式

            const isMyTurn = (this.currentActionSeat === this.myServerSeat);
            if (this.turnStatusLabel) {
                if (!isMyTurn) {
                    this.turnStatusLabel.string = "回合外";
                    this.turnStatusLabel.color = new Color(255, 255, 255);
                } else {
                    this.turnStatusLabel.string = "请出牌";
                    this.turnStatusLabel.color = new Color(255, 255, 255);
                }
            }

            this.netManager.sendPlayerAction(ActionType.PASS);
            this.resetActionButtons();
            this.cancelSelectionMode(); // 彻底退出吃牌选择模式并放下牌
            log("【交互】玩家选择了过，放弃当前所有拦截权限。");
        }
    }

    // --- 结算与 UI 弹窗 ---

    private onReceiveRoundSummary(msg: MainMessage) {
        const summary = msg.roundSummary;
        if (!summary) return;

        const sortedScores = summary.scores ? [...summary.scores] : [];
        sortedScores.sort((a, b) => {
            const scoreA = a.scoreChange === undefined ? 0 : a.scoreChange;
            const scoreB = b.scoreChange === undefined ? 0 : b.scoreChange;
            return scoreB - scoreA; 
        });

        // 将整个 summary 对象传进去，方便内部读取牌型数据
        this.showResultPanel(sortedScores, summary, true);
    }

    private onReceiveFinalResult(msg: MainMessage) {
        const result = msg.finalResult; 
        if (!result) return;

        log(`【终局结算】总冠军: ${result.winnerNickname}`);
        const mockScores = result.leaderBoard.map(info => ({
            nickname: info.nickname, 
            scoreChange: 0,
            currentTotalScore: info.totalScore,
            rank: info.rank
        }));
        
        this.showResultPanel(mockScores, `游戏结束！最终赢家: ${result.winnerNickname}`, false);
    }

    private showResultPanel(scoresList: any[], summary: any, isRoundSummary: boolean) {
        const panelNode = instantiate(this.resultPanelPrefab);
        this.node.addChild(panelNode); 

        // 1. 拼接标题：番型列表
        const leaderboardNode = panelNode.getChildByName("Leaderboard");
        const titleLabel = leaderboardNode.getChildByName("Title")?.getComponent(Label);
        if (titleLabel) {
            if (isRoundSummary && summary) {
                const fanStr = (summary.fanNames && summary.fanNames.length > 0) ? summary.fanNames.join(" + ") : "平胡";
                const tFan = summary.totalFan === undefined ? 0 : summary.totalFan;
                titleLabel.string = ` ${fanStr}`;
            } else {
                titleLabel.string = "游戏结束：最终排名";
            }
        }

        // 2. 渲染排行榜分数
        const scrollView = panelNode.getComponentInChildren(ScrollView);
        if(scrollView) {
            const content = scrollView.content;
            if (content) {
                content.removeAllChildren(); 

                scoresList.forEach((info, index) => {
                    const item = instantiate(this.rankItemPrefab);
                    content.addChild(item); 

                    const label = item.getComponentInChildren(Label);
                    if (label) {
                        // 防范 seatIndex 为 undefined 的情况
                        const sIndex = info.seatIndex === undefined ? 0 : info.seatIndex;
                        const name = info.nickname ? info.nickname : `座位 ${sIndex}`;
                        
                        const changeVal = info.scoreChange === undefined ? 0 : info.scoreChange;
                        // 强制给正数加上 '+' 号，增加视觉辨识度
                        const changeStr = changeVal > 0 ? `+${changeVal}` : `${changeVal}`;
                        const totalStr = info.currentTotalScore === undefined ? 0 : info.currentTotalScore;
                        const jCount = info.jokerCount === undefined ? 0 : info.jokerCount;

                        // 根据结算类型，采用不同的排版格式
                        if (isRoundSummary) {
                            label.string = `玩家 ${name} / 财神 ${jCount} 张 / 本局 ${changeStr} / 总分 ${totalStr}`;
                        } else {
                            label.string = `${index + 1}. ${name} ——  ${totalStr} 分`;
                        }
                        
                        // 如果是“我”，用醒目的深黄色或红色高亮；其他人用黑色
                        if (sIndex === this.myServerSeat) {
                            label.color = new Color(165, 154, 25); // 这里填你想高亮的颜色
                        } else {
                            label.color = new Color(0, 0, 0); // 其他人黑色
                        }
                    }
                });
            }
        }

        // 3. 渲染赢家的牌型结构
        if (isRoundSummary && summary) {
            const leaderboardNode = panelNode.getChildByName("Leaderboard");
            const cardsArea = leaderboardNode ? leaderboardNode.getChildByName("WinningCardsArea") : null;
            
            if (cardsArea) {
                cardsArea.removeAllChildren();
                
                // A. 渲染副露 (碰/杠)
                const melds = summary.winnerMelds || [];
                melds.forEach((meld: any) => {
                    const mCards = meld.cards || [];
                    mCards.forEach((c: any) => {
                        const node = instantiate(this.cardPrefab);
                        cardsArea.addChild(node);
                        c.type = c.type === undefined ? 0 : c.type;
                        c.value = c.value === undefined ? 0 : c.value;
                        // isRaw 为 false，自动走 getVisualData 换皮逻辑
                        this.updateCardUI(node, true, c, false);
                    });
                });

                // B. 渲染暗手牌
                const handCards = summary.winnerHandCards || [];
                
                // 严格双重排序 (白板数据强制最左 -> 类型 -> 数值)
                handCards.sort((a: any, b: any) => {
                    const isAJoker = (a.type === 4 && a.value === 5);
                    const isBJoker = (b.type === 4 && b.value === 5);

                    if (isAJoker && !isBJoker) return -1;
                    if (!isAJoker && isBJoker) return 1;

                    const typeA = a.type === undefined ? 0 : a.type;
                    const typeB = b.type === undefined ? 0 : b.type;
                    if (typeA !== typeB) return typeA - typeB;
                    const valA = a.value === undefined ? 0 : a.value;
                    const valB = b.value === undefined ? 0 : b.value;
                    return valA - valB;
                });
                
                handCards.forEach((c: any) => {
                    const node = instantiate(this.cardPrefab);
                    cardsArea.addChild(node);
                    c.type = c.type === undefined ? 0 : c.type;
                    c.value = c.value === undefined ? 0 : c.value;
                    // 同样走换皮逻辑，真财神会变成假皮肤并泛蓝，假皮肤会变成白板
                    this.updateCardUI(node, true, c, false);
                });

                // C. 渲染胡的那张目标牌 (单独高亮)
                if (summary.winningCard) {
                    const winCard = summary.winningCard;
                    const node = instantiate(this.cardPrefab);
                    cardsArea.addChild(node);
                    winCard.type = winCard.type === undefined ? 0 : winCard.type;
                    winCard.value = winCard.value === undefined ? 0 : winCard.value;
                    
                    // 第四个参数传 true，触发黄色高亮 (如果是真财神，updateCardUI 内部会优先涂蓝)
                    this.updateCardUI(node, true, winCard, true); 
                }
            }
        }

        // 4. 确认按钮交互
        const confirmBtnNode = leaderboardNode.getChildByName("ConfirmButton");
        if (confirmBtnNode) {
            confirmBtnNode.on(Button.EventType.CLICK, () => {
                if (isRoundSummary && this.netManager) {
                    log("【交互】玩家点击继续，发送准备下一局指令 (1009)");
                    this.netManager.sendReadyNextMatch();
                    
                    // 销毁面板后，锁定当前牌桌并提示等待
                    this.resetActionButtons(); 
                    if (this.turnStatusLabel) {
                        this.turnStatusLabel.string = "等待其他玩家确认...";
                        this.turnStatusLabel.color = new Color(255, 255, 0); 
                    }
                } else if (!isRoundSummary) {
                    log("【交互】终局退出，返回大厅");
                    director.loadScene("LobbyScene");
                }
                panelNode.destroy();
            }, this);
        }
    }

    // --- 视觉与数据转换辅助 ---
    // 第五个参数 isRaw 代表是否原样渲染，默认为 false
    private updateCardUI(cardNode: Node, isFaceUp: boolean, info: any, isNewlyDrawn: boolean = false, isRaw: boolean = false, isRestricted: boolean = false) {
        const cardUI = cardNode.getComponent(CardUI);
        if (cardUI && info) {
            cardUI.type = info.type; 
            cardUI.value = info.value;
        }

        const front = cardNode.getChildByName("Front");
        const back = cardNode.getChildByName("Back");
        if (!front || !back) return;

        front.active = isFaceUp;
        back.active = !isFaceUp;
        if (!isFaceUp) return; 

        const bgSprite = front.getComponent(Sprite);
        const labelNode = front.getChildByName("ValueLabel"); 
        const label = labelNode ? labelNode.getComponent(Label) : null;
        const faceNode = front.getChildByName("Face");
        const faceSprite = faceNode ? faceNode.getComponent(Sprite) : null;

        let dType = info.type;
        let dValue = info.value;
        let isJoker = false;

        // 如果是原样渲染（用于财神提示框），直接跳过视觉置换
        if (!isRaw) {
            const visualData = this.getVisualData(info);
            dType = visualData.dType;
            dValue = visualData.dValue;
            isJoker = visualData.isJoker;
        }

        let hasTexture = false;
        const spriteName = `mj_${dType}_${dValue}`;
        const frame = this.tileCache.get(spriteName);
        
        if (frame && faceSprite) {
            faceSprite.spriteFrame = frame;
            hasTexture = true;
        }

        if (faceNode) faceNode.active = hasTexture;
        
        if (labelNode && label) {
            labelNode.active = !hasTexture; 
            if (!hasTexture) {
                label.string = this.getMahjongCardStr(dType, dValue);
                const colors = [Color.WHITE, new Color(220, 20, 60), new Color(30, 144, 255), new Color(34, 139, 34), Color.BLACK];
                label.color = colors[dType] || Color.BLACK;
            }
        }

        let targetColor = Color.WHITE;
        if (isJoker) {
            targetColor = new Color(160, 230, 255); // 真财神：永远优先是蓝色
        } else if (isRestricted) {
            targetColor = new Color(170, 170, 170); 
        } else if (isNewlyDrawn) {
            targetColor = new Color(255, 255, 150); // 最右侧的新牌：淡黄色
        }

        if (bgSprite) bgSprite.color = targetColor;
        if (faceSprite) faceSprite.color = targetColor;
    }

    // 在 GameManager 中新增翻译函数
    private getVisualData(realInfo: any) {
        if (!this.caishenInfo) return { dType: realInfo.type, dValue: realInfo.value, isJoker: false };

        const isRealWhiteDragon = (realInfo.type === 4 && realInfo.value === 5); 
        const isRealCaishenValue = (realInfo.type === this.caishenInfo.type && realInfo.value === this.caishenInfo.value);

        let dType = realInfo.type;
        let dValue = realInfo.value;
        let isJoker = false;

        if (isRealWhiteDragon) {
            // 白板数据 -> 穿财神衣服
            dType = this.caishenInfo.type;
            dValue = this.caishenInfo.value;
            isJoker = true;
        } else if (isRealCaishenValue) {
            // 财神数据 -> 穿白板衣服
            dType = 4;
            dValue = 5;
        }

        return { dType, dValue, isJoker };
    }

    private getMahjongCardStr(type: number, value: number): string {
        const types = ["", "万", "条", "筒", ""];
        if (type === 4) {
            const zi = ["", "东", "南", "西", "北", "白", "发", "中"];
            return zi[value] || "?";
        }
        return value.toString() + types[type];
    }

    /**
     * 极其明显的按钮视觉控制
     * isActive = true: 恢复原本鲜艳颜色，可以点击
     * isActive = false: 变成纯灰白/变暗，无法点击
     */
    private setActionButtonState(btn: Button, isActive: boolean, r?: number, g?: number, b?: number) {
        if (!btn || !btn.node) return;

        // 1. 控制真实交互权限
        btn.node.active = true; 
        btn.interactable = isActive;

        // 2. 强力控制视觉表现
        const sprite = btn.getComponent(Sprite);
        if (sprite) {
            // 开启 Cocos 原生的灰度滤镜
            sprite.grayscale = !isActive; 

            // 【安全防御】极其严格的 number 兜底转换，全部默认置为 0
            const safeR = r === undefined ? 0 : r;
            const safeG = g === undefined ? 0 : g;
            const safeB = b === undefined ? 0 : b;

            // 亮起时使用传入的专属颜色，暗去时使用统一的深灰色
            sprite.color = isActive ? new Color(safeR, safeG, safeB, 255) : new Color(120, 120, 120, 255);
        }
    }

    /**
     * 重置所有操作按钮（默认显示，但置灰不可交互）
     */
    private resetActionButtons() {
        if (this.btnChi) this.setActionButtonState(this.btnChi, false);
        if (this.btnPong) this.setActionButtonState(this.btnPong, false);
        if (this.btnKong) this.setActionButtonState(this.btnKong, false);
        if (this.btnHu)   this.setActionButtonState(this.btnHu, false);
        if (this.btnPass) this.setActionButtonState(this.btnPass, false);
    }

    private getLocalSeatIndex(serverSeat: number, totalPlayers: number): number {
        if (this.myServerSeat === -1 || totalPlayers <= 0) return 0;
        return ((serverSeat - this.myServerSeat + totalPlayers) % totalPlayers);
    }

    /**
     * 【修改】仅清理个人区域的手牌和成牌区，公牌区交由增量逻辑维护
     */
    private clearPersonalTable() {
        this.seatNodes.forEach(node => node.removeAllChildren());
        this.handArea.removeAllChildren();
    }

    onDestroy() {
        director.off("FinalResult", this.onReceiveFinalResult, this);
        director.off("RoundSummary", this.onReceiveRoundSummary, this);
    }

    /**
     * 自定义胡牌检测逻辑
     * @param handCards 玩家当前的暗手牌数组
     * @param targetCard 别人打出的目标牌 (如果是自摸，则传 null)
     * @param formedSets 玩家已经摆在桌面的组合数组 (吃、碰、杠)
     * @returns 判定结果、总番数、番型名称列表
     */
    private checkCanHu(handCards: any[], targetCard: any | null, formedSets: any[], isQiangGang: boolean = false): { canHu: boolean, totalFan: number, fanNames: string[] } {
        if (!handCards) return { canHu: false, totalFan: 0, fanNames: [] };
        
        let canHu = false;
        let totalFan = 0;
        let fanNames: string[] = [];

        // 1. 判断是否自摸
        let isZiMo = false;
        if (!targetCard) {
            isZiMo = true;
            if (handCards.length > 0) {
                const lastCard = handCards[handCards.length - 1];
                targetCard = { type: lastCard.type || 0, value: lastCard.value || 0 };
            }
        }

        // 2. 拼装基础数组并抽离财神
        let checkArray = handCards.map(card => ({ type: card.type || 0, value: card.value || 0 }));
        if (!isZiMo && targetCard) {
            checkArray.push({ type: targetCard.type || 0, value: targetCard.value || 0 });
        }

        checkArray.sort((a, b) => {
            if (a.type !== b.type) return a.type - b.type;
            return a.value - b.value;
        });

        const normalCards: any[] = [];
        let jokerCount = 0;
        checkArray.forEach(c => {
            // 数据底层是白板 (4, 5) 即为真万能牌
            if (c.type === 4 && c.value === 5) jokerCount++;
            else normalCards.push(c);
        });

        // 3. 验证八对 (17张牌：8对 + 1单张)
        let isEightPairs = false;
        let oddCount = 0; // 统计普通牌里无法成对的单牌数量
        const safeFormedSets = formedSets || [];
        
        if (safeFormedSets.length === 0 && checkArray.length === 17) {
            let i = 0;
            while (i < normalCards.length) {
                let count = 1;
                while (i + 1 < normalCards.length && normalCards[i].type === normalCards[i + 1].type && normalCards[i].value === normalCards[i + 1].value) {
                    count++; i++;
                }
                if (count % 2 !== 0) oddCount++; 
                i++;
            }
            
            let neededJoker = Math.max(0, oddCount - 1);
            if (neededJoker <= jokerCount) {
                isEightPairs = true;
                canHu = true;
            }
        }

        // 4. 验证平胡 (如果不是八对)
        if (!canHu) {
            if (this.checkPingHu(normalCards, jokerCount)) {
                canHu = true;
            }
        }

        // ==========================================
        // 5. 温州麻将 0/1/2 番数精准判定逻辑
        // ==========================================
        if (canHu) {
            if (jokerCount === 3) {
                // 【最高优先级】：拥有三张财神且能胡，无视一切直接 2 番
                totalFan = 2; 
                fanNames.push("三财神");
            } 
            else if (checkArray.length === 2) {
                // 【最高优先级】：手牌仅剩 1 张而胡（考虑自摸，因此手牌小于等于 2 张一定就是单吊）
                totalFan = 2; 
                fanNames.push("单吊");
            }
            else if (jokerCount === 0) {
                // 【无财神分支】
                if (isEightPairs) {
                    totalFan = 2; fanNames.push("硬八对");
                } else {
                    totalFan = 1; fanNames.push("硬胡");
                }
            } 
            else {
                // 【有 1~2 张财神分支】
                if (isEightPairs) {
                    // 如果单牌 <= 1，说明财神没去顶替普通牌，而是财神自己跟自己配对
                    if (oddCount <= 1) {
                        totalFan = 2; fanNames.push("双财神硬八对");
                    } else {
                        totalFan = 1; fanNames.push("软八对");
                    }
                } else {
                    // 平胡形态
                    // 核心修复：使用 this.isAfterChiPong 判断是否属于吃碰后当回合的“假自摸”
                    if (isZiMo && !this.isAfterChiPong) {
                        totalFan = 1; fanNames.push("自摸");
                    } else if (isQiangGang) {
                        totalFan = 1; fanNames.push("抢杠");
                    } else {
                        // 不是自摸，验证【财神归位】
                        let isGuiWei = false;
                        if (this.caishenInfo && jokerCount >= 1) {
                            // 降维回溯：抽离 1 张万能牌，将它的“原皮”作为实体牌塞入，重新测算
                            let testCards = [...normalCards];
                            testCards.push({ type: this.caishenInfo.type, value: this.caishenInfo.value });
                            testCards.sort((a, b) => {
                                if (a.type !== b.type) return a.type - b.type;
                                return a.value - b.value;
                            });
                            
                            // 只要少 1 张财神也能胡，说明这 1 张财神刚好充当了它的皮，触发归位
                            if (this.checkPingHu(testCards, jokerCount - 1)) {
                                isGuiWei = true;
                            }
                        }

                        if (isGuiWei) {
                            totalFan = 1; fanNames.push("财神归位");
                        } else {
                            // 强行用财神填补了其他窟窿，就是 0 番（屁胡）
                            totalFan = 0; fanNames.push("软胡");
                        }
                    }
                }
            }
        }

        return { canHu, totalFan, fanNames }; 
    }

    /**
     * 辅助方法：抽离出的平胡（雀头+面子）深度检测
     */
    private checkPingHu(normalCards: any[], jokerCount: number): boolean {
        const uniqueCards = [];
        for (let i = 0; i < normalCards.length; i++) {
            if (i === 0 || normalCards[i].type !== normalCards[i - 1].type || normalCards[i].value !== normalCards[i - 1].value) {
                uniqueCards.push(normalCards[i]);
            }
        }

        // 假设 A：直接用 2 张财神当雀头
        if (jokerCount >= 2) {
            if (this.checkMianZiWithJoker([...normalCards], jokerCount - 2)) return true;
        }

        // 假设 B：用普通牌当雀头
        for (let card of uniqueCards) {
            const cardCount = normalCards.filter(c => c.type === card.type && c.value === card.value).length;

            // 用 1张实牌 + 1张财神 当雀头
            if (jokerCount >= 1 && cardCount >= 1) {
                const temp = [...normalCards];
                temp.splice(temp.findIndex(c => c.type === card.type && c.value === card.value), 1);
                if (this.checkMianZiWithJoker(temp, jokerCount - 1)) return true;
            }

            // 用 2张相同的实牌 当雀头
            if (cardCount >= 2) {
                const temp = [...normalCards];
                temp.splice(temp.findIndex(c => c.type === card.type && c.value === card.value), 1);
                temp.splice(temp.findIndex(c => c.type === card.type && c.value === card.value), 1);
                if (this.checkMianZiWithJoker(temp, jokerCount)) return true;
            }
        }
        return false;
    }

    /**
     * 带万能牌的面子（顺子/刻子）深度回溯检测
     * @param cards 剥离了万能牌后的普通牌数组（必须已排序）
     * @param jokerCount 当前剩余可用的万能牌数量
     */
    private checkMianZiWithJoker(cards: any[], jokerCount: number): boolean {
        if (cards.length === 0) return true; 

        const first = cards[0];
        const type = first.type;
        const val = first.value;

        // --- 剪枝：如果这张牌连 [自身+2个财神] 都凑不齐，直接宣告此路不通 ---
        if (jokerCount < 0) return false; 

        // --- 路线 1：作为刻子 (AAA) ---
        // 逻辑不变：尽量用实牌，不够用财神
        let sameCount = 0;
        for (let i = 0; i < cards.length; i++) {
            if (cards[i].type === type && cards[i].value === val) sameCount++;
            else break;
        }
        for (let useReal = Math.min(sameCount, 3); useReal >= 1; useReal--) {
            let needJ = 3 - useReal;
            if (jokerCount >= needJ) {
                let nextCards = [...cards];
                nextCards.splice(0, useReal);
                if (this.checkMianZiWithJoker(nextCards, jokerCount - needJ)) return true;
            }
        }

        // --- 路线 2：作为顺子 (ABC) ---
        // 字牌(type = 4)不能做顺子
        if (type <= 3) {
            // 情况 A: [A, A+1, A+2] - A是开头
            if (val <= 7) {
                if (this.trySequence(cards, jokerCount, type, val, val + 1, val + 2)) return true;
            }

            // 情况 B: 8 是中间 (必须消耗一个财神充当 9)
            if (val === 8 && jokerCount >= 1) {
                // 7 必须用财神，所以我们只需要在剩下的牌里找 9
                if (this.trySequence(cards, jokerCount - 1, type, -1, val, val + 1)) return true;
            }
        }

        return false;
    }

    /**
     * 辅助方法：尝试在手中寻找特定牌凑顺子，找不到则用财神补
     */
    private trySequence(cards: any[], jokers: number, type: number, v1: number, v2: number, v3: number): boolean {
        let neededJ = 0;
        let nextCards = [...cards];
        let targets = [v1, v2, v3];

        for (let t of targets) {
            if (t === -1) continue; // -1 表示该位置已被财神预定
            let idx = nextCards.findIndex(c => c.type === type && c.value === t);
            if (idx !== -1) {
                nextCards.splice(idx, 1);
            } else {
                neededJ++;
            }
        }

        if (jokers >= neededJ) {
            return this.checkMianZiWithJoker(nextCards, jokers - neededJ);
        }
        return false;
    }
}