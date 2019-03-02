/**
 * Copyright {C} 2014 Coinport Inc. <http://www.coinport.com>
 *
 */

namespace java com.coinport.bitway.data

include "data.thrift"

typedef data.RedeliverFilters              RedeliverFilters
typedef data.TAddressStatus                TAddressStatus
typedef data.CryptoCurrencyAddressType     CryptoCurrencyAddressType
typedef data.BlockIndex                    BlockIndex
typedef data.Currency                      Currency
typedef data.Invoice                       Invoice
typedef data.ExchangeRate                  ExchangeRate
typedef data.Merchant                      Merchant
typedef data.CashAccount                   CashAccount
typedef data.DelayedPost                   DelayedPost
typedef data.TransferType                  TransferType
typedef data.CryptoCurrencyTransferItem    CryptoCurrencyTransferItem
typedef data.ExchangeOrder                 ExchangeOrder
typedef data.TradeStrategy                 TradeStrategy
typedef data.InvoiceData                   InvoiceData

struct TSimpleState {
    1: RedeliverFilters filters
}

struct TAccountState {
    1: RedeliverFilters filters
    2: map<i64, Merchant> accountMap
    3: map<string, i64> tokenMap
    4: optional map<i64, i64> idMap
    5: optional map<string, i64> verificationTokenMap
    6: optional map<string, i64> passwordResetTokenMap
    7: optional i64 lastMerchantId
    8: map<i64, Merchant> settleAccountMap //Deprecated
    9: optional map<Currency, CashAccount> aggregationAccount
}

struct TBlockchainState {
    1: Currency currency
    2: RedeliverFilters filters
    3: list<BlockIndex> blockIndexes
    4: map<CryptoCurrencyAddressType, set<string>> addresses
    5: map<string, TAddressStatus> addressStatus
    6: i64 lastAlive
    7: map<string, i64> addressUidMap
    8: set<string> sigIdsSinceLastBlock
    9: optional map<string, string> privateKeysBackup
    10: optional map<string, i64> addressTTL
    11: optional i64 lockForWithdrawal
    12: optional map<i64, list<InvoiceData>> merchant2CreateInvoice
}

struct TInvoiceState {
    1: RedeliverFilters filters
    2: map<string, Invoice> invoices
    3: i64 lastTransferId
    4: i64 lastTransferItemId
    5: map<Currency, i64> lastBlockHeight
    6: map <TransferType, map<i64, CryptoCurrencyTransferItem>> transferMap
    7: map <TransferType, map<i64, CryptoCurrencyTransferItem>> succeededMap
    8: map <TransferType, map<string, i64>> sigId2MinerFeeMapInnner
}

struct TNotificationState {
    1: RedeliverFilters filters
    2: list<DelayedPost> posts
}

struct TTradingState {
    1: RedeliverFilters filters
    2: map<string, ExchangeOrder> submittedOrders
    3: map<string, TradeStrategy> tradeStrategies
}

struct TBillWriterState {
    1: RedeliverFilters filters
    2: i64 billId
}
