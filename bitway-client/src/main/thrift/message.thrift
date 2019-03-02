/**
 * Copyright {C} 2014 Coinport Inc. <http://www.coinport.com>
 *
 */

namespace java com.coinport.bitway.data

include "data.thrift"

///////////////////////////////////////////////////////////////////////
/////////////////////////////  MESSAGES  //////////////////////////////

typedef data.ErrorCode                          _ErrorCode
typedef data.Currency                           _Currency
typedef data.InvoiceStatus                      _InvoiceStatus
typedef data.Contact                            _Contact
typedef data.Merchant                           _Merchant
typedef data.CashAccount                        _CashAccount
typedef data.InvoiceData                        _InvoiceData
typedef data.Invoice                            _Invoice
typedef data.InvoiceUpdate                      _InvoiceUpdate
typedef data.OrderUpdate                        _OrderUpdate
typedef data.InvoiceSummary                     _InvoiceSummary
typedef data.ExchangeRate                       _ExchangeRate
typedef data.BitcoinTx                          _BitcoinTx
typedef data.BitcoinBlock                       _BitcoinBlock
typedef data.InvoiceNotification                _InvoiceNotification
typedef data.PaymentNotification                _PaymentNotification
typedef data.SellParam                          _SellParam
typedef data.EmailType                          _EmailType
typedef data.Depth                              _Depth
typedef data.Exchange                           _Exchange
typedef data.TradePair                          _TradePair
typedef data.BlockIndex                         _BlockIndex
typedef data.CryptoCurrencyTransferInfo         _CryptoCurrencyTransferInfo
typedef data.CryptoCurrencyBlock                _CryptoCurrencyBlock
typedef data.CryptoAddress                      _CryptoAddress
typedef data.CryptoCurrencyAddressType          _CryptoCurrencyAddressType
typedef data.BitwayRequestType                  _BitwayRequestType
typedef data.TransferType                       _TransferType
typedef data.CryptoCurrencyTransaction          _CryptoCurrencyTransaction
typedef data.BitcoinTxPort                      _BitcoinTxPort
typedef data.DelayedPost                        _DelayedPost
typedef data.L2MarketExchangeOrder              _L2MarketExchangeOrder
typedef data.BillItem                           _BillItem
typedef data.BillType                           _BillType
typedef data.AccountTransfer                    _AccountTransfer
typedef data.TransferConfig                     _TransferConfig
typedef data.AccountTransfersWithMinerFee       _AccountTransfersWithMinerFee
typedef data.TransferStatus                     _TransferStatus
typedef data.Cursor                             _Cursor
typedef data.SpanCursor                         _SpanCursor
typedef data.ExchangeOrder                      _ExchangeOrder
typedef data.SubmitOrderResult                  _SubmitOrderResult
typedef data.QueryOrderResult                   _QueryOrderResult
typedef data.TradeAction                        _TradeAction
typedef data.Fee                                _Fee
typedef data.QuantAccount                       _QuantAccount
typedef data.AssetStatus                        _AssetStatus
typedef data.ExchangeAccountInfo                _ExchangeAccountInfo
typedef data.GetAccountResult                   _GetAccountResult
typedef data.MerchantStatus                     _MerchantStatus

struct DoRegister                   { 1: _Merchant merchant, 2: string password }
struct RegisterFailed               { 1: _ErrorCode error}
struct RegisterSucceeded            { 1: _Merchant merchant }

struct DoLogin                      { 1: string email, 2: string password }
struct LoginFailed                  { 1: _ErrorCode error}
struct LoginSucceeded               { 1: i64 merchantId, 2: string email}

struct CreateApiToken               { 1: i64 merchantId, 2: optional string token, 3: optional string secretKey, 4: optional _Merchant merchant }
struct ApiTokenCreated              { 1: i64 merchantId, 2: string apiToken }
struct DestoryApiToken              { 1: string apiToken }
struct ApiTokenDestroied            { 1: i64 merchantId, 2: string apiToken}
struct ApiTokenNotExist             { 1: string apiToken }
struct DestoryAllApiTokens          { 1: i64 merchantId   }
struct AllApiTokensDestroied        { 1: i64 merchantId   }
struct DisableAccount               { 1: i64 merchantId   }
struct AccountDisabled              { 1: i64 merchantId   }
struct UpdateSecretKey              { 1: i64 merchantId, 2: optional string secretKey, 3: optional i64 timestamp }
struct SecretKeyUpdated             { 1: i64 merchantId, 2: optional string secretKey }
struct MigrateSecretKey             { 1: i64 merchantId, 2: optional i64 timestamp }

struct ValidateToken                { 1: string apiToken}
struct AccessGranted                { 1: _Merchant merchant}
struct AccessDenied                 { 1: _ErrorCode error}

struct DoValidateRegister           { 1: string token }
struct ValidateRegisterSucceeded    { 1: i64 merchantId }
struct ValidateRegisterFailed       { 1: _ErrorCode error }

struct DoRequestPasswordReset         { 1: string email, 2: optional string token }
struct RequestPasswordResetSucceeded  { 1: i64 id, 2: string email }
struct RequestPasswordResetFailed     { 1: _ErrorCode error }

struct DoResetPassword              { 1: string newPassword, 2: string token }
struct ResetPasswordSucceeded       { 1: i64 id, 2: string email }
struct ResetPasswordFailed          { 1: _ErrorCode error}

struct QueryMerchantById            { 1: i64 id }
struct QueryMerchantResult          { 1: optional _Merchant merchant }

struct QueryAccountsById            { 1: i64 id }
struct QueryAccountsByIdResult      { 1: optional map<_Currency, _CashAccount> accounts }

struct AdjustAggregationAccount       { 1: _CashAccount adjustment }
struct AdjustAggregationAccountResult { 1: _ErrorCode error = data.ErrorCode.OK, 2: optional map<_Currency, _CashAccount> accounts }


struct AdjustMerchantAccount          { 1: i64 merchantId, 2: _CashAccount adjustment }
struct AdjustMerchantAccountResult    { 1: _ErrorCode error = data.ErrorCode.OK, 2: optional map<_Currency, _CashAccount> accounts }

struct DoUpdateMerchantFee          { 1: _Merchant merchant }
struct DoUpdateMerchant             { 1: _Merchant merchant }
struct UpdateMerchantSucceeded      { 1: _Merchant merchant }
struct UpdateMerchantFailed         { 1: _ErrorCode error }
struct DoSetMerchantStatus          { 1: i64 id, 2: _MerchantStatus status, 3: optional i64 timestamp, 4: optional _Merchant merchant}
struct SetMerchantStatusResult      { 1: _ErrorCode error }

struct SendVerificationMail           { 1: string email, 2: string verifyCode }
struct SendVerificationMailSucceeded  { 1: string email }

struct CreateInvoice                { 1: _InvoiceData invoiceData, 2: i64 merchantId, 3: optional string merchantName, 4: optional string paymentAddress, 5: optional i64 expiredTime}
struct InvoiceCreated               { 1: _Invoice invoice }
struct InvoiceCreationFailed        { 1: _ErrorCode resultCode, 2: string reason }

struct InvoiceUpdated               { 1: list<_InvoiceUpdate> invoiceUpdates}
struct PaymentReceived              { 1: string id, 2: binary payment }
struct InvoicePartialUpdate         { 1: list<_InvoiceUpdate> invoiceUpdates}
struct OrderLogisticsUpdated        { 1: list<_OrderUpdate> orderUpdates}
struct OrderLogisticsUpdatedResult  { 1: _ErrorCode error}

struct RequestTransferFailed        {1: _ErrorCode error}
struct RequestTransferSucceeded     {1: _AccountTransfer transfer}

struct InvoiceComplete              { 1: list<_Invoice> completes }
struct MerchantSettle               { 1: _AccountTransfer transfer }
struct MerchantRefund               { 1: _AccountTransfer transfer }
struct DoPaymentRequest             { 1: _AccountTransfer transfer }
struct MerchantBalanceRequest       { 1: i64 merchantId, 2: list<_Currency> currencyList }
struct MerchantBalanceResult        { 1: map<_Currency, double> balanceMap, 2: optional _ErrorCode error, 3: optional map<_Currency, double> pendingMap }
struct DoCancelSettle               { 1: _AccountTransfer transfer }
struct DoCancelTransfer             { 1: _AccountTransfer transfer }
struct LockAccountRequest           { 1: i64 merchantId, 2: _Currency currency, 3: i64 amount, 4: bool onlyCheck = false}
struct LockAccountResult            { 1: bool isSuccess }

struct DoSendCoin                   { 1: list<_AccountTransfer> transfers }
struct LockCoinRequest              { 1: list<_AccountTransfer> lockInfos}
struct LockCoinResult               { 1: list<_AccountTransfer> lockInfos, 2: optional _ErrorCode error}
struct UnlockCoinRequest            { 1: list<_CryptoCurrencyTransferInfo> lockInfo}
struct QueryBlockChainAmount        { }
struct QueryBlockChainAmountResult  { 1: double totalAmount 2: double lockedAmount }

struct AdminConfirmTransferFailure  {1: _AccountTransfer transfer, 2:_ErrorCode error }
struct AdminConfirmTransferSuccess  {1: _AccountTransfer transfer }
struct AdminSetTransferStatus       {1: _AccountTransfer transfer }
struct AdminCommandResult           {1: _ErrorCode error = data.ErrorCode.OK}

struct CryptoTransferResult         {1: map<string, _AccountTransfersWithMinerFee> multiTransfers, 2: optional InvoiceComplete invoiceComplete }

struct QueryInvoiceById             { 1: string id, 2: bool liveOnly = true } // returns QueryInvoiceResult
struct QueryInvoice                 { 1: optional i64 merchantId, 2: i32 skip, 3: i32 limit, 4: optional list<_InvoiceStatus> statusList, 5: optional i64 from, 6: optional i64 to, 7: optional _Currency currency, 8: optional string orderId} // returns QueryInvoiceResult
struct QueryInvoiceByIds            { 1: list<string> ids }
struct QueryInvoiceResult           { 1: list<_Invoice> invoices, 2: i32 count}
struct QueryMerchantBalance         { 1: i64 merchantId, 2: list<_Currency> currencyList}
struct QueryMerchantBalanceResult   { 1: i64 merchantId, 2: map<_Currency, double> balanceMap}

struct QueryBill                    { 1: i64 merchantId, 2: i32 skip, 3: i32 limit, 4: optional _Currency currency, 5: optional i64 from, 6: optional i64 to, 7: optional _BillType billType}
struct QueryBillResult              { 1: list<_BillItem> items, 2: i32 count}

struct BestBtcRatesUpdated          { 1: map<_Currency, _ExchangeRate> bidRates, 2: map<_Currency, _ExchangeRate> askRates }
struct GetBestBidRates              { }
struct GetBestAskRates              { }
struct BestBidRates                 { 1: map<_Currency, _ExchangeRate> bidRates }
struct BestAskRates                 { 1: map<_Currency, _ExchangeRate> bidRates }

/*
    获取实时汇率
*/
struct GetCurrentExchangeRate       { 1: _Currency baseCurrency, 2: _Currency targetCurrency, 3: optional double baseCurrencyAmount, 4: optional double targetCurrencyAmount}
struct CurrentExchangeRateResult    { 1: _ErrorCode code, 2: optional double buyingRate, 3: optional double sellingRate}

struct WatchInvoiceStatus           { 1: string invoiceId}
struct InvoiceStatusChanged         { 1: _InvoiceStatus status, 2: i64 updateTime}

struct BitcoinTxSeen                { 1: _BitcoinTx newTx }
struct BitcoinBlockSeen             { 1: _BitcoinBlock newBlock 2: optional i64 reorgHeight, 3: optional i32 confirmNum }
struct InvoiceDebugRequest          { 1: string id, 2: _InvoiceStatus status }

struct NotifyMerchants              { 1: list<_InvoiceNotification> invoiceNotificaitons }
struct NotifyPayments               { 1: list<_PaymentNotification> paymentNotificaitons }
struct ResendNotify                 { 1: _Invoice invoice, 2: i64 timestamp }
struct ResendSuccessed              { 1: _DelayedPost post, 2: i64 timestamp }
struct ResendFailed                 { 1: _DelayedPost post, 2: i64 timestamp }

struct SellBitcoin                  { 1: list<_SellParam> sellParams}
// add by xiaolu
struct SellBTC                      { 1: _L2MarketExchangeOrder order }
struct BitcoinSold                  { }
struct BuyBTC                       { 1: _L2MarketExchangeOrder order }

// deprecated
struct SellBitcoinAtL2Market        { 1: map<_Exchange, _L2MarketExchangeOrder> orders }
struct BuyBitcoinAtL2Market         { 1: map<_Exchange, _L2MarketExchangeOrder> orders }

struct SubmitExchangeOrder          { 1: _ExchangeAccountInfo info, 2: _ExchangeOrder order}
struct SubmitExchangeOrderResult    { 1: optional _SubmitOrderResult result}

struct SubmitTradeAction            { 1: map<_Exchange, _ExchangeAccountInfo> infos, 2: _TradeAction action }
struct SubmitTradeActionResult      { 1: list<_ExchangeOrder> orders }

struct CancelExchangeOrder          { 1: _ExchangeAccountInfo info, 2: string id, 3: _TradePair pair }
struct CancelExchangeOrderResult    { 1: bool result }

struct GetAllOpeningOrders          { 1: _ExchangeAccountInfo info, 2: _TradePair pair }
struct GetAllOpeningOrdersResult    { 1: list<_QueryOrderResult> orders }

struct GetAssets                    { 1: _ExchangeAccountInfo info }
struct GetAssetsResult              { 1: _GetAccountResult assets }

struct DepthUpdated                 { 1: map<_TradePair, _Depth> depths }

struct OpeningOrdersReceived        { 1: map<_Exchange, list<_QueryOrderResult>> orders }

struct MarketDataUpdated            { 1: map<_TradePair, _Depth> depths }

struct CreateSimpleExchangeStrategy {1: optional _TradeAction openAction, 2: optional _TradeAction closeAction, 3: i64 referAccountId, 4: optional string superStrategyId, 5: optional list<string> mergedStrategiesId }

struct CreateDepthMakerStrategy     {1: i64 referAccountId }

struct GetPlatformBalances          { 1: double cnyPrice}
struct PlatformBalanceUpdated       { 1: double balance}

struct DoSendEmail                  {1: string email, 2: _EmailType emailType, 3: map<string, string> params}
struct TakeSnapshotNow              {1: string desc, 2: optional i32 nextSnapshotinSeconds}
struct QueryRecoverStats            {1: i32 trival = 0 /* messange cannot be empty */}
struct DumpStateToFile              {1: string actorPath}

////////// MarketAgentActor
struct GetDepths        {}
struct GetDepthsResult  {1: _Currency currency, 2: map<_Exchange, _Depth> depths}

////////// BlockchainView
struct QueryAsset                             {1: _Currency currency}
struct QueryAssetResult                       {1: _Currency currency, 2: map<_CryptoCurrencyAddressType, double> amounts, 3: optional double lockedForWithdrawal}

////////// BlockchainActor
struct AllocateNewAddress                      {1: _Currency currency, 2: i64 userId, 3: optional string assignedAddress}
struct AllocateNewAddressResult                {1: _Currency currency, 2: _ErrorCode error = data.ErrorCode.OK, 3: optional string address}
struct AdjustAddressAmount                     {1: _Currency currency, 2: string address, 3: i64 adjustAmount}
struct AdjustAddressAmountResult               {1: _Currency currency, 2: _ErrorCode error = data.ErrorCode.OK, 3: string address, 4: optional i64 adjustAmount}

// remove this message later
struct MultiCryptoCurrencyTransactionMessage   {1: _Currency currency, 2: list<_CryptoCurrencyTransaction> txs, 3: optional _BlockIndex reorgIndex, 4: optional i32 confirmNum, 5: optional i64 timestamp}

////////// Bitway nodejs
struct CleanBlockChain                         {1: _Currency currency} // use this only if want re-start up from fatal new branch error. and must make sure the new coming block height is higher than previous highest block height
struct SyncHotAddresses                        {1: _Currency currency}
struct SyncHotAddressesResult                  {1: _ErrorCode error, 2: set<_CryptoAddress> addresses}
struct SyncPrivateKeys                         {1: _Currency currency, 2: optional set<string> pubKeys}
struct SyncPrivateKeysResult                   {1: _ErrorCode error, 2: set<_CryptoAddress> addresses}
struct GetMissedCryptoCurrencyBlocks           {1: list<_BlockIndex> startIndexs, 2: _BlockIndex endIndex} // returned (startIndex, endIndex]
struct GenerateAddresses                       {1: i32 num}
struct MultiTransferCryptoCurrency             {1: _Currency currency, 2: map<_TransferType, list<_CryptoCurrencyTransferInfo>> transferInfos}
struct BitwayTransferRequest                   {1: optional MultiTransferCryptoCurrency transfers, 2: optional list<_CryptoCurrencyTransferInfo> unlocks }

struct GenerateAddressesResult                 {
                                                   1: _ErrorCode error,
                                                   2: optional set<_CryptoAddress> addresses,
                                                   3: optional _CryptoCurrencyAddressType addressType
                                               }
struct CryptoCurrencyBlockMessage              {1: optional _BlockIndex reorgIndex, /* BlockIndex(None, None) means in another branch */ 2: _CryptoCurrencyBlock block, 3: optional i64 timestamp}
struct TransferCryptoCurrency                  {1: _Currency currency, 2: list<_CryptoCurrencyTransferInfo> transferInfos, 3: _TransferType type}
//struct TransferCryptoCurrencyResult            {1: _Currency currency, 2: _ErrorCode error = data.ErrorCode.OK, 3: optional TransferCryptoCurrency request, 4: optional i64 timestamp}
struct MultiTransferCryptoCurrencyResult       {1: _Currency currency, 2: _ErrorCode error = data.ErrorCode.OK, 3: optional map<_TransferType, list<_CryptoCurrencyTransferInfo>> transferInfos, 4: optional i64 timestamp}
struct SendRawTransaction                      {1: string signedTx}
struct SendRawTransactionResult                {1: _ErrorCode error = data.ErrorCode.OK}
struct BitwayRequest                           {
                                                   1: _BitwayRequestType type
                                                   2: _Currency currency
                                                   3: optional GenerateAddresses generateAddresses
                                                   4: optional GetMissedCryptoCurrencyBlocks getMissedCryptoCurrencyBlocksRequest
                                                   5: optional TransferCryptoCurrency transferCryptoCurrency
                                                   6: optional SyncHotAddresses syncHotAddresses
                                                   7: optional MultiTransferCryptoCurrency multiTransferCryptoCurrency
                                                   8: optional SyncPrivateKeys syncPrivateKeys
                                                   9: optional SendRawTransaction rawTransaction
                                               }
struct BitwayMessage                           {
                                                   1: _Currency currency
                                                   2: optional GenerateAddressesResult generateAddressResponse
                                                   3: optional _CryptoCurrencyTransaction tx
                                                   4: optional CryptoCurrencyBlockMessage blockMsg
                                                   5: optional SyncHotAddressesResult syncHotAddressesResult
                                                   6: optional SyncPrivateKeysResult syncPrivateKeysResult
                                               }

struct TryFetchAddresses                       {1: i32 trival}
struct TrySyncHotAddresses                     {1: i32 trival}
struct FetchAddresses                          {1: _Currency currency}
struct ListenAtRedis                           {1: i32 trival}
struct PingFeProxy                             {1: i32 trival}

struct MessageNotSupported                     {1: string event}

struct QueryTransfer                           {1: optional i64 merchantId, 2: optional _Currency currency, 3: optional _TransferStatus status, 4: optional _SpanCursor spanCur, 5:list<_TransferType> types, 6: _Cursor cur, 7: optional bool fromAdmin, 8: optional i64 transferId }
struct QueryTransferResult                     {1: list<_AccountTransfer> transfers, 2: i64 count}

struct QueryMerchants                          {1: optional i64 merchantId, 2: optional string email, 3: optional _MerchantStatus status, 4: optional _SpanCursor spanCur, 5: _Cursor cursor}
struct QueryMerchantsResult                    {1: list<_Merchant> merchants, 2: i64 count}

struct FetchDepth                              {1: _Exchange exchangeType, 2: optional _TradePair pair}

struct SyncDepth                               {1: _Exchange exchangeType, 2: _TradePair pair, 3: optional _Depth depth, 4: i64 timestamp}

struct SubmittedOrders                         {1: map<_Exchange, _ExchangeOrder> orders}

struct QueryOrderStatus                        {1: _ExchangeAccountInfo info, 2: string exOrderId 3: string id 4: _TradePair pair}

struct QueryOrderStatusResult                  {1: string id, 2: _QueryOrderResult result}

struct GetDepth                                {1: optional _TradePair pair, 2: optional i32 depth}

struct GetDepthResult                          {1: map<_TradePair, _Depth> depths}

struct GetExchangeHealth                       {1: optional _TradePair pair}

struct GetExchangeHealthResult                 {1: map<_Exchange, i64> health}

struct PaymentMessage                          {
                                                   1: optional QueryAssetResult assets
                                                   2: optional _TradeAction action
                                               }

struct QuantMessage                            {
                                                   1: optional BestBtcRatesUpdated rates
                                                   2: optional DoSendCoin sendCoin
                                               }

// messages of quant account
struct CreateAccount                          {1: _QuantAccount account}
struct CreateAccountResult                    {1: i64 id}
struct UpdateAccount                          {1: _QuantAccount account}
struct UpdateAccountResult                    {1: bool result, 2: optional string desp}
struct QueryAccount                           {1: list<i64> ids}
struct QueryAccountResult                     {1: list<_QuantAccount> accounts}
struct QueryAggregateAssets                   {1: list<i64> ids}
struct QueryAggregateAssetsResult             {1: map<_Currency, map<_AssetStatus, double>> assets}

// messages of quant account_snapshot
struct QuerySnapshots                         {1: i64 id, 2: optional i32 num}
struct QuerySnapshotsResult                   {1: list<_QuantAccount> accounts}
struct QueryChangeRate                        {1: i64 id}
struct QueryChangeRateResult                  {1: map<i32, map<_Currency, double>> rates}
struct QueryAllChangeRate                     {1: optional list<i64> ids}
struct QueryAllChangeRateResult               {1: map<i64, map<i32, map<_Currency, double>>> rates}
