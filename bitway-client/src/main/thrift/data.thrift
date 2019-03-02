/**
 * Copyright {C} 2014 Coinport Inc. <http://www.coinport.com>
 *
 */

namespace java com.coinport.bitway.data

///////////////////////////////////////////////////////////////////////
///////////////////////////// ERROR CODES /////////////////////////////
///////////////////////////////////////////////////////////////////////

enum ErrorCode {
    OK                         = 0
    UNKNOWN                    = 1
    INVALID_TOKEN              = 2
    INVALID_AMOUNT             = 3
    NOT_ENOUGH_ADDRESS_IN_POOL = 4
    PARTIALLY_FAILED           = 5
    ALL_FAILED                 = 6
    WITHDRAWAL_TO_BAD_ADDRESS  = 7
    INSUFFICIENT_HOT           = 8
    INVALID_SIGNED_TX          = 9
    INSUFFICIENT_FUND          = 10

    // register & login
    EMAIL_ALREADY_REGISTERED   = 100
    EMAIL_NOT_VERIFIED         = 101
    MERCHANT_NOT_EXIST         = 102
    PASSWORD_NOT_MATCH         = 103
    MERCHANT_REVOKED           = 104

    // param validation
    PARAM_EMPTY                = 200
    INVALID_EMAIL_FORMAT       = 201
    INVALID_PASSWORD_FORMAT    = 202
    REPEAT_NOT_EQUAL           = 203
    INVALID_EMAIL_VERIFY_CODE  = 204

    // Deposit/Withdrawal
    ALREADY_CONFIRMED          = 301
    TRANSFER_NOT_EXIST         = 302
    UNSUPPORT_TRANSFER_TYPE    = 303
    MERCHANT_CANCELLED         = 304
    MERCHANT_AUTHEN_FAIL       = 305
    UNABLE_TRADE_ON_MARKET     = 306
    LOCK_REFUND_ACCOUNT_FAIL   = 308
    TIME_OUT                   = 309
    INVOICE_NOT_EXIST          = 310
    MERCHANT_NOT_VALID         = 311

    // invoice
    EXCEED_MAX_PRICE           = 401
    PRICE_IS_NEGATIVE          = 402
    FETCH_ADDRESS_FAIL         = 403
    CALCULATE_PRICE_FAIL       = 404
    EXCEED_MAX_TIMES           = 405

    // exchange
    INVALID_CURRENCY           = 501
}

enum Currency {
    UNKNOWN = 0
    CNY = 1
    USD = 2

    // must make sure that the min index of crypto currency is 1000
    BTC  = 1000
    DOGE = 1100
    LTC  = 1200
    BTSX = 1300
    XRP  = 1400
    BC   = 1500
    DRK  = 1600
    NXT  = 1700
    VRC  = 1800
    ZET  = 1900
}


enum InvoiceStatus {
    NEW         = 0
    PAID        = 1
    CONFIRMED   = 2
    COMPLETE    = 3
    EXPIRED     = 4
    INVALID     = 5
    REFUNDED    = 6
    REORGING_CONFIRMED = 7
    REFUND_FAILED = 8
}

enum TransactionSpeed {
    HIGH        = 0
    MEDIUM      = 1
    LOW         = 2
}

enum EmailType {
    REGISTER_VERIFY = 1
    LOGIN_TOKEN = 2
    PASSWORD_RESET_TOKEN = 3
    MONITOR = 4
}

enum Exchange {
    OKCOIN_CN     = 0
    OKCOIN_EN     = 1
    BTCCHINA      = 2
    HUOBI         = 3
    BITSTAMP      = 4
    BTER          = 5
    BTC38         = 6
    COINPORT      = 7
    PAYMENT       = 8
}

enum TransferStatus {
    PENDING              = 0
    ACCEPTED             = 1
    CONFIRMING           = 2
    CONFIRMED            = 3
    SUCCEEDED            = 4
    FAILED               = 5 // this will happen when confirmation satisfied but can't spend it
    REORGING             = 6
    REORGING_SUCCEEDED   = 7
    CANCELLED            = 8
    REJECTED             = 9
    PROCESSING           = 10
    LOCK_FAILED          = 11
    LOCK_INSUFFICIENT    = 12
    LOCKED               = 13
    BITWAY_FAILED        = 14
    PROCESSED_FAIL       = 15
    CONFIRM_BITWAY_FAIL  = 16
    REORGING_FAIL        = 17
    LOCK_INSUFFICIENT_FAIL = 18
    REFUND_TIME_OUT      = 19
    REFUND_LOCK_ACCOUNT_ERR = 20
    REFUND_INSUFFICIENT_FAIL = 21
    UNABLE_TRADE_FAIL    = 22
}

enum TransferType {
    DEPOSIT     = 0
    WITHDRAWAL  = 1
    USER_TO_HOT = 2
    HOT_TO_COLD = 3
    COLD_TO_HOT = 4
    SEND_COIN   = 5
    UNKNOWN     = 6
    SETTLE      = 7
    REFUND      = 8
    PAYMENT     = 9 //pay for someone
}

enum CryptoCurrencyAddressType {
    USER   = 0
    UNUSED = 1
    HOT    = 2
    COLD   = 3
}

enum BitwayRequestType {
    GENERATE_ADDRESS      = 0
    TRANSFER              = 1
    GET_MISSED_BLOCKS     = 2
    SYNC_HOT_ADDRESSES    = 3
    MULTI_TRANSFER        = 4
    SYNC_PRIVATE_KEYS     = 5
    SEND_RAW_TRANSACTION  = 6
}

enum BitwayResponseType {
    GENERATE_ADDRESS      = 0
    TRANSACTION           = 1
    TRANSFER              = 2
    AUTO_REPORT_BLOCKS    = 3
    GET_MISSED_BLOCKS     = 4
    SYNC_HOT_ADDRESSES    = 5
    SYNC_PRIVATE_KEYS     = 6
    SEND_RAW_TRANSACTION  = 7
}

enum OrderType {
    LIMIT_ORDER  = 0
    MARKET_ORDER = 1
}

enum OrderStatus {
    SUBMITTED      = 0
    CANCELED       = 1
    UNDEALED       = 2
    PARTIAL_DEALED = 3
    TOTAL_DEALED   = 4
    FAILED         = 5
    UNKNOWN        = 6
}

enum AssetStatus {
    AVAILABLE = 0
    FROZEN    = 1
}

enum BillType {
    INVOICE     = 0
    FEE         = 1
    REFUND      = 2
    SETTLE      = 3
    PAYMENT     = 4
}

enum Country {
    CHINA  = 0
    USA    = 1
    EUROS  = 2
}

enum TradeActionStatus {
    PROCESSING = 0
    FINISH     = 1
    TERMINATE  = 2
    TO_CLEAN_DUST = 3
}

enum StrategyStatus {
    PROCESSING     = 0
    FINISH         = 1
    TERMINATE      = 2
    MERGED         = 3
    TO_CLEAN_DUST  = 4
}

enum StrategyDriveType {
    INVOICE_DRIVE           = 0
    REFUND_DRIVE            = 1
    ARBITRAGE               = 2
    COMPOSE                 = 3
    DEPTH_MAKER_DRIVE       = 4
    KLINE_MAKER_DRIVE       = 5
    SIMPLE_EXCHANGE_DRIVE   = 6
}

enum MerchantStatus {
    PENDING   = 0
    ACTIVE    = 1
    SUSPENDED = 2
    REVOKED   = 3
}

///////////////////////////////////////////////////////////////////////
/////////////////////////////     DATA    /////////////////////////////
///////////////////////////////////////////////////////////////////////

struct Contact {
    1:  string name
    2:  optional string address
    3:  optional string address2
    4:  optional string city
    5:  optional string state
    6:  optional string zip
    7:  optional string country
    8:  optional string phone
    9:  optional string email
    10: optional string defaultRedirectURL // for merchant only
}

struct BankAccount {
    1: string bankName
    2: string ownerName
    3: string accountNumber
    4: optional string branchBankName
    5: optional Country country
    6: optional string achRoutingNumber
    7: optional string usTaxId
    8: optional string bankSwiftCode
    9: optional string bankTransitNumber
    10: optional string bankStreetAddress
    11: optional string bankCity
    12: optional string bankPostalCode
    13: optional string bicBranchCode
    15: optional Currency perferredCurrency
}

struct CashAccount {
    1: Currency currency
    2: i64 available
    3: i64 pendingWithdrawal
}

struct Fee {
    1: i64 payer
    2: optional i64 payee  // pay to coinport if None
    3: Currency currency
    4: i64 amount
    5: optional string basis
    6: optional double externalAmount
}

struct PaymentData {
    1: optional Currency currency
    2: optional i64 amount
    3: optional double externalAmount
    4: optional string notificationUrl
    5: optional string notificationEmail
    6: optional string note
}

struct AccountTransfer {
    1:  i64 id
    2:  i64 merchantId
    3:  TransferType type
    4:  Currency currency
    5:  i64 amount
    6:  TransferStatus status = TransferStatus.PENDING
    7:  optional i64 created
    8:  optional i64 updated
    9:  optional ErrorCode reason
    10: optional Fee fee
    11: optional string address
    12: optional i64 confirm
    13: optional string txid
    14: optional double externalAmount
    15: optional TransferType innerType
    16: optional Currency refundCurrency
    17: optional double refundAmount // amount of fee is included, shoud sub when buy btc from market
    18: optional i64 refundInternalAmount // amount of fee is included, shoud sub when buy btc from market
    19: optional string invoiceId
    20: optional double refundAccAmount //Deprecated
    21: optional PaymentData paymentData
}

struct AccountTransfersWithMinerFee {
    1: list<AccountTransfer> transfers
    2: optional i64 minerFee
}

struct Merchant {
    1: i64 id
    2: optional string name
    3: optional Contact contact
    4: map<string, string> tokenAndKeyPairs
    5: optional string email
    6: optional bool emailVerified
    7: optional string passwordHash
    8: optional string verificationToken
    9: optional string passwordResetToken
    10: optional set<BankAccount> bankAccounts
    11: optional map<Currency, CashAccount> settleAccount
    12: double feeRate = 0.0 // normal rate is 0.005
    13: double constFee = 0.0
    14: optional string btcWithdrawalAddress
    15: MerchantStatus status = MerchantStatus.PENDING
    16: optional i64 created
    17: optional i64 updated
    18: optional string secretKey
}

struct InvoiceData {
    1:  double price
    2:  Currency currency
    3:  optional string posData
    4:  optional string notificationURL
    5:  i32 transactionSpeed = 1 // user want confirmation number
    6:  bool fullNotifications = false
    7:  optional string notificationEmail
    8:  optional string redirectURL
    9:  bool physical = false
    10: optional string orderId
    11: optional string itemDesc
    12: optional string itemCode
    13: optional Contact buyer
    14: optional Contact merchant
    15: double displayPrice
    16: Currency displayCurrency
    17: double fee = 0.0
    18: TransactionSpeed tsSpeed = TransactionSpeed.MEDIUM
    19: optional string customTitle
    20: optional string customLogoUrl
    21: optional string theme
    22: optional string redirectMethod
    23: optional i64 created
    24: optional string customFooter
}

struct Invoice {
    1:  i64 merchantId
    2:  optional string merchantName
    3:  string id // invoice's unique id
    4:  InvoiceStatus status = InvoiceStatus.NEW
    5:  InvoiceData data
    6:  double btcPrice
    7:  i64 invoiceTime // create time
    8:  i64 expirationTime = 0
    9:  i64 invalidTime = 0
    10: i64 updateTime = 0
    11: string paymentAddress
    12: optional double btcPaid
    13: optional i64 lastPaymentBlockHeight
    14: optional string txid
    15: optional i64 currentTime
    16: optional double rate
    17: optional list<binary> payments
    18: optional double refundAccAmount
    19: optional i64 paidTime
}

struct InvoiceUpdate {
    1: string id
    2: InvoiceStatus status
    3: optional double btcPaid
    4: optional i64 lastPaymentBlockHeight
    5: optional string txid
    6: optional double refundAccAmount // Use as increment amount, not accumulate amount
}

struct Order {
    1: string id
    2: string invoiceId
    3: i64 merchantId
    4: optional string itemDesc
    5: optional string itemCode
    6: bool physical = false
    7: optional string logisticsCode
    8: optional string logisticsOrgCode
    9: optional string logisticsOrgName
}

struct OrderUpdate {
    1: string orderId
    2: i64 merchantId
    3: optional string logisticsCode
    4: optional string logisticsOrgCode
    5: optional string logisticsOrgName
}

struct NotificationData {
    1: optional double refundAccAmount
}

struct InvoiceSummary {
    1:  string id
    2:  double price
    3:  Currency currency
    4:  double btcPrice
    5:  InvoiceStatus status
    6:  i64 invoiceTime
    7:  i64 expirationTime
    8:  optional string posData
    9:  optional i64 currentTime
    10: optional string orderId
    11: optional NotificationData data
    12: optional string redirectUrl
}

struct InvoiceNotification {
    1: string notificationURL
    2: Invoice invoice
}

struct PaymentNotification {
    1: string notificationUrl
    2: AccountTransfer payment
}

struct InvoiceNotificationInfo {
    1: string id
    2: double price
    3: string currency
    4: double btcPrice
    5: i64 invoiceTime
    6: i64 expirationTime
    7: string posData
    8: i64 currentTime
    9: string status
    10: string orderId
    11: optional NotificationData data
}

struct BillItem {
    1: i64 id
    2: i64 merchantId
    3: double amount
    4: Currency currency
    5: BillType bType
    6: i64 timestamp
    7: optional string invoiceId
    8: optional string memo
    9: optional Invoice invoice
    10: optional string address
    11: optional string note
}

struct ExchangeRate {
    // ask: map<btcAmount, cnyPrice per btc>
    // bid: map<cnyAmount, cnyPrice per btc>
    1: map<double, double> piecewisePrice
}

struct Cursor {
    1: i32 skip
    2: i32 limit
}

struct SpanCursor {
    1: i64 from
    2: i64 to
}

struct BitcoinTxPort {
    1: string address
    2: optional double amount
}

struct BitcoinTx {
    1: optional string sigId
    2: optional string txid
    3: optional list<BitcoinTxPort> inputs
    4: optional list<BitcoinTxPort> outputs
    5: optional i64 minerFee
    6: i64 timestamp
    7: optional TransferStatus status
    8: optional TransferType txType
    9: optional i64 theoreticalMinerFee
    10: optional list<i64> ids
}

struct BitcoinBlock {
    1: i64 height
    2: list<BitcoinTx> txs
    3: i64 timestamp
}

struct DepthItem {
    1: double price
    2: double rawPrice
    3: double quantity
    4: optional Exchange exchange
    5: optional i64 expiredTime
}

struct ExchangeParam {
    1: double minExchangeAmount
    2: double exchangeFee
    3: double withdrawalCnyFee
    4: double minWithdrawalCnyFee
    5: double withdrawalBtcFee
}

struct Depth {
    1: list<DepthItem> bids
    2: list<DepthItem> asks
}

struct SellParam {
    1: i64 quantity
    2: double price
    3: optional string btcAddress
}

struct RedeliverFilterData {
    1: list<i64> processedIds
    2: i32 maxSize
}

struct RedeliverFilters {
    1: map<string, RedeliverFilterData> filterMap
}

// nodejs needed
struct BlockIndex {
    1: optional string id
    2: optional i64 height
}

struct AddressStatusResult {
    1: optional string txid // last tx with include the address
    2: optional i64 height // tx first included block height
    3: i64 confirmedAmount
}

struct CryptoAddress {
    1: string address
    2: optional string privateKey
}

struct TAddressStatus {
    1: optional string txid // last tx with include the address
    2: optional i64 height // tx first included block height
    3: map<i64, list<i64>> books
}

struct CryptoCurrencyTransactionPort {
    1: string address
    2: optional double amount
    3: optional i64 internalAmount
    4: optional i64 userId
}

struct CryptoCurrencyTransferInfo {
    1: i64 id
    2: optional string to
    3: optional i64 internalAmount
    4: optional double amount
    5: optional string from
    6: optional ErrorCode error
}

struct CryptoCurrencyTransaction {
    1:  optional string sigId
    2:  optional string txid
    3:  optional list<i64> ids
    4:  optional list<CryptoCurrencyTransactionPort> inputs
    5:  optional list<CryptoCurrencyTransactionPort> outputs
    6:  optional BlockIndex prevBlock
    7:  optional BlockIndex includedBlock
    8:  optional TransferType txType
    9:  TransferStatus status
    10: optional i64 timestamp
    11: optional i64 minerFee
    12: optional double theoreticalMinerFee
}

struct CryptoCurrencyTransferItem {
    1: i64 id
    2: Currency currency
    3: optional string sigId
    4: optional string txid
    5: optional i64 merchantId
    6: optional CryptoCurrencyTransactionPort from
    7: optional CryptoCurrencyTransactionPort to
    8: optional i64 includedBlockHeight
    9: optional TransferType txType
    10: optional TransferStatus status
    11: optional i64 accountTransferId
    12: optional i64 created
    13: optional i64 updated
    14: optional i64 minerFee
    15: optional TransferType innerType
}

struct CryptoCurrencyBlock {
    1: BlockIndex index
    2: BlockIndex prevIndex
    3: list<CryptoCurrencyTransaction> txs
}

struct CryptoCurrencyNetworkStatus {
    1: optional string id
    2: optional i64 height
    3: optional i64 heartbeatTime
    4: optional i64 queryTimestamp
}

struct DelayedPost {
    1: i64 timestamp
    2: string url
    3: InvoiceSummary summary
    4: i32 maxTry
    5: i32 tried
}

// deprecated
struct L2MarketExchangeOrder {
    1: double amount
    2: optional double price
    3: OrderType orderType
    4: optional Currency currency
}

struct TradePair {
    1: Currency outCurrency
    2: Currency inCurrency
}

struct ExchangeOrder {
    1: string id
    2: Exchange exchangeType
    3: TradePair pair
    4: double amount
    5: optional double price
    6: optional string exOrderId
    7: OrderStatus status = OrderStatus.SUBMITTED
    8: double dealedAmount = 0
}

struct SubmitOrderResult {
    1: bool isSucceed
    2: optional string exOrderId
    3: optional string orderId
}

struct GetAccountResult {
    1: map<Currency, map<AssetStatus, double>> assets
}

struct QueryOrderResult {
    1: string id
    2: string type
    3: double amount
    4: optional double price
    5: double dealedAmount
    6: OrderStatus status
}

struct TransferConfig {
    1: optional set<Currency> manualCurrency
    2: optional bool enableAutoConfirm
}

// one action for one strategy
struct ExchangeAccountInfo {
    1: string userId
    2: string accessKey
    3: string secretKey
}

struct TradeAction {
    1: string id
    2: optional string referStrategyId
    3: TradePair pair
    4: double amount
    5: double price
    6: TradeActionStatus status
    7: optional double executedAmount
    8: optional double avgExecutedPrice
    9: optional list<ExchangeOrder> orders
    10: optional set<Exchange> exchangeRange
}

// need support strategy merge
struct TradeStrategy {
    1: string id
    2: StrategyDriveType driveType
    3: StrategyStatus status = StrategyStatus.PROCESSING
    4: optional list<TradeAction> openPositionActions
    5: optional list<TradeAction> closePositionActions
    6: optional list<string> mergedStrategiesId
    7: i64 referAccountId
    8: optional string superStrategyId
    9: i64 createdTime
    10: i64 updateTime
    11: optional TradePair pair
}

struct AccountExchange {
   1: Exchange exchange
   2: string secretKey
   3: string accessKey
   4: string userId
   5: map<Currency, map<AssetStatus, double>> assets
}

struct QuantAccount {
    1: i64 id
    2: string name
    3: string desp
    4: i64 createdTime
    5: i64 updateTime
    6: list<AccountExchange> exchanges
}

