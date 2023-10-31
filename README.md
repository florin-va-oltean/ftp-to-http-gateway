# Application Level Gateway FTP->HTTP

This is a custom command for [Apache MINA FTP server](https://mina.apache.org/ftpserver-project/index.html). 

Please get used with the Apache MINA FTP server and especially with the configuration via XML. 

## Delivery

It is intended to be delivered as an uberjar; it builds therefore with 
```shell
  mvn clean
  mvn package
```

## Launching 

```shell
  java -Dlogback.configurationFile=resources/logback.xml  -jar target/ftp-extension-commands-1.0.0.jar resources/ftpd-typical.xml
```

## Configuration

Please expand on the sample from file `ftpd-typical.xml` in `resource` directory. 

*And read Apache FTP server docs*. 

My command simply takes the following parameters:
- httpUrl - the base url where it will actually store the file send via FTP STOR command
- httpAuthorization - value of `Authorization header`; up to you to put correct value (i.e. base64 encoded for basic authentication, for example)
- httpConnectionTimeout - timeout for connecting to http server
- httpReadTimeout - timeout to complete request and read response from http server

Essentially, it receives FTP STOR command and it generates a HTTP POST based on above parameters; the body of the post is the actual file. 

Quirks:
- I had to declare all commands once again otherwise it did not use my custom command; this is because I actually redefine a standard command. For full list of commands see `org.apache.ftpserver.command.CommandFactoryFactory`. 
- It is a good idea to use TLS; in this case, better to define your own certificate. 


