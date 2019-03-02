/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

var CryptoAgentManager = require('./crypto/crypto_agent_manager').CryptoAgentManager,
    DataTypes          = require('../../../gen-nodejs/data_types'),
    Currency           = DataTypes.Currency;
var fs = require('fs');
var dog = {
    currency: Currency.DOGE,
    cryptoConfig: {
        cryptoRpcConfig: {
            protocol: 'http',
            user: 'user',
            pass: 'pass',
            host: 'bitcoind',
            port: '44555',
        },
        minConfirm: 1,
        checkInterval : 1000,
        walletPassPhrase: ""
    },
    redisProxyConfig: {
        currency: Currency.DOGE,
        ip: 'bitway',
        port: '6379',
    }
};
var btc = {
    currency: Currency.BTC,
    cryptoConfig: {
        cryptoRpcConfig: {
            protocol: 'http',
            user: 'user',
            pass: 'pass',
            host: 'bitway',
            port: '8332',
        },
        minConfirm: 1,
        checkInterval : 1000,
        walletPassPhrase: ""
    },
    redisProxyConfig: {
        currency: Currency.BTC,
        ip: 'bitway',
        port: '6379',
    }
};

var configs = [ btc ];

//var readline = require('readline');
//var rl = readline.createInterface({
//    input: process.stdin,
//    output: process.stdout,
//    terminal: true
//});
// 
//function hidden(query, callback) {
//    var stdin = process.openStdin();
//    process.stdin.on("data", function(char) {
//        char = char + "";
//        switch (char) {
//            case "\n":
//            case "\r":
//            case "\u0004":
//                stdin.pause();
//                break;
//            default:
//                process.stdout.write("\033[2K\033[200D" + query + Array(rl.line.length+1).join("*"));
//                break;
//        }
//    });
//
//    rl.question(query, callback);
//}

//hidden("password : ", function(password) {
//    console.log("Your password : " + password);
//    if (password && password.length > 7) {
//        for (var i = 0; i < configs.length; i++) {
//            configs[i].cryptoConfig.walletPassPhrase = password;
//        }
//    } else {
//        console.log("Password isn't correct!");
//        console.log("node index.js [password]");
//        process.exit(0);
//    }
//    var manager = new CryptoAgentManager(configs);
//    manager.start();
//
//    var logo = "\n" +
//    " _    _ _                     \n" +
//    "| |__(_) |___ __ ____ _ _  _  \n" +
//    "| '_ \\ |  _\\ V  V / _` | || | \n" +
//    "|_.__/_|\\__|\\_/\\_/\\__,_|\\_, | \n" +
//    "                        |__/  \n";
//    console.log(logo);
//});

fs.readFile('./pw', function(error, data){
    if (!error) {
        if (data.length != 0) {
            var pw = JSON.parse(data);
            var password = pw.testPw;
            if (password && password.length > 7) {
                console.log('use password');
                for (var i = 0; i < configs.length; i++) {
                    configs[i].cryptoConfig.walletPassPhrase = password;
                }
            } else {
                console.log("Password isn't correct!");
                console.log("node index.js [password]");
                process.exit(0);
            }
        } else {
            console.log("data.length == 0");
            console.log("Password isn't correct!");
            console.log("node index.js [password]");
            process.exit(0);
        }
    } else {
        console.log('error %j', error);
        console.log("Password isn't correct!");
        console.log("node index.js [password]");
    }

    var manager = new CryptoAgentManager(configs);
    manager.start();

    var logo = "\n" +
    " _    _ _                     \n" +
    "| |__(_) |___ __ ____ _ _  _  \n" +
    "| '_ \\ |  _\\ V  V / _` | || | \n" +
    "|_.__/_|\\__|\\_/\\_/\\__,_|\\_, | \n" +
    "                        |__/  \n";
    console.log(logo);
});
