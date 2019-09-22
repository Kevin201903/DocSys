	//DocEdit类	
    var DocEdit = (function () {
    	var md;	//mdeditor对象
      	//自动保存定时器
      	var autoSaveTimer;
      	var timerState = 0;
    
      	function editorInit(content)
      	{
      		console.log("DocEdit editorInit");

      		var params = {
               width: "100%",
               height: $(document).height()-70,
               path : 'static/markdown/lib/',
               markdown : "",	//markdown的内容默认务必是空，否则会出现当文件内容是空的时候显示默认内容
               //toolbar  : false,             // 关闭工具栏
               codeFold : true,
               searchReplace : true,
               saveHTMLToTextarea : true,      // 保存 HTML 到 Textarea
               htmlDecode : "style,script,iframe|on*",            // 开启 HTML 标签解析，为了安全性，默认不开启
               emoji : true,
               taskList : true,
               tocm: true,          			// Using [TOCM]
               tex : true,                      // 开启科学公式 TeX 语言支持，默认关闭
               //previewCodeHighlight : false,  // 关闭预览窗口的代码高亮，默认开启
               flowChart : true,
               sequenceDiagram : true,
               //dialogLockScreen : false,      // 设置弹出层对话框不锁屏，全局通用，默认为 true
               //dialogShowMask : false,     // 设置弹出层对话框显示透明遮罩层，全局通用，默认为 true
               //dialogDraggable : false,    // 设置弹出层对话框不可拖动，全局通用，默认为 true
               dialogMaskOpacity : 0.2,    // 设置透明遮罩层的透明度，全局通用，默认值为 0.1
               dialogMaskBgColor : "#000", // 设置透明遮罩层的背景颜色，全局通用，默认为 #fff
               imageUpload : true,
               imageFormats : ["jpg","JPG", "jpeg","JPEG","gif","GIF","png", "PNG","bmp","BMP", "webp","WEBP",],
               imageUploadURL : "/DocSystem/Doc/uploadMarkdownPic.do",
               onchange : function () {
                   console.log("onchange");                   
                   if(gEdit == true)
                   {
                       var newContent = this.getMarkdown();
            	       debounce.call(newContent);
    		       }
               },
               onpreviewing : function () {
                   console.log("onpreviewing");
                   exitEditWiki();
               },
               onpreviewed :function () {
                   console.log("onpreviewed");
                   editWiki();
               },
               onload : function () {
                   console.log("onload");	//这是markdown初始化完毕的回调（此时才可以访问makdown的接口）
   	    		   this.previewing();
   	       		   this.setMarkdown(content);
               }
       		};
       		
      		//editormd was defined in editormd.js
       		md = editormd("vdocPreview",params);
      	}
        
    	function editorLoadmd(content) 
    	{
    		console.log("DocEdit editorLoadmd()");       		
    		md.setMarkdown(content);
        }
        
        function loadmd(content)
        {
    		if(!content)
    		{
    			content = "";
    		}
    		
			console.log("loadmd content:", content);				
    		
			if(md)
   			{
      			editorLoadmd(content);               				
   			}
			else
			{
				editorInit(content);	
    		}
        }
		      		
		function editorSwitch(edit)
    	{
    		console.log("DocEdit editorSwitch() edit:"+edit);
    		gEdit = edit;
    		
    		if(!md)
       		{
    			showErrorMessage("please call editorInit firstly");
       			return;
       		}
       		
    		if(edit == false)
	    	{
	    		md.previewing();
	    	}
	    	else
	    	{
	    		md.previewed();
	    	}
    	}
    	
    	function startAutoTmpSaver()
		{ 
			console.log("DocEdit.startAutoTmpSaver timerState:" + timerState);
			if(timerState == 0)
			{
				timerState = 1;
				autoSaveTimer = setInterval(function () {
		        	if(debounce.getStatus1() == 1)
		    		{
		    			console.log("autoTmpSaveWiki");
		    	    	debounce.clearStatus1();
		    			tmpSaveDoc(gDocId, debounce.get());
		    		}
		    	},20000);
		    }
		}
	
		function stopAutoTmpSaver(){
			console.log("DocEdit.stopAutoTmpSaver timerState:" + timerState);
			if(timerState == 1)
			{
				timerState = 0;
				clearInterval(autoSaveTimer);
			}
		}
		
	    //文件临时保存操作
	    function tmpSaveDoc(docId, content){
			console.log("tmpSaveDoc: docId:" + docId);
			
			var node = getNodeById(docId);
			if(node && node == null)
			{
	            console.log("临时保存失败 :" , (new Date()).toLocaleDateString());
	            bootstrapQ.msg({
					msg : "临时保存失败 : 文件不存在",
					type : 'danger',
					time : 1000,
				});	
	            return;
			}
			
	        $.ajax({
	            url : "/DocSystem/Doc/tmpSaveDocContent.do",
	            type : "post",
	            dataType : "json",
	            data : {
	            	reposId: gReposId,
	                docId : docId,
	                pid: node.pid,
	                path: node.path,
	                name: node.name,
	                content : content,
	            },
	            success : function (ret) {
	                if( "ok" == ret.status ){
	                    console.log("临时保存成功 :" , (new Date()).toLocaleDateString());
	                    bootstrapQ.msg({
									msg : "临时保存成功 :" + (new Date()).toLocaleDateString(),
									type : 'success',
									time : 1000,
						});
	                }else {
	                    //bootstrapQ.alert("临时保存失败:"+ret.msgInfo);
	                    bootstrapQ.msg({
							msg : "临时保存失败 :" + +ret.msgInfo,
							type : 'danger',
							time : 1000,
						});
	                }
	            },
	            error : function () {
	                //bootstrapQ.alert("临时保存异常");
	                bootstrapQ.msg({
						msg : "临时保存失败 :服务器异常",
						type : 'danger',
						time : 1000,
					});
	            }
	        });

	    }
	    
	    //文件临时Delete操作
	    function deleteTmpSavedContent(docId){
			console.log("deleteTmpSavedDocContent: docId:" + docId);
			
			var node = getNodeById(docId);
			if(node && node == null)
			{
	            console.log("删除临时保存内容失败 :" , (new Date()).toLocaleDateString());
	            bootstrapQ.msg({
					msg : "删除临时保存内容失败 : 文件不存在",
					type : 'danger',
					time : 1000,
				});	
	            return;
			}
			
	        $.ajax({
	            url : "/DocSystem/Doc/deleteTmpSavedDocContent.do",
	            type : "post",
	            dataType : "json",
	            data : {
	            	reposId: gReposId,
	                docId : docId,
	                pid: node.pid,
	                path: node.path,
	                name: node.name,
	            },
	            success : function (ret) {
	                if( "ok" == ret.status ){
	                    console.log("删除临时保存内容成功 :" , (new Date()).toLocaleDateString());
	                    bootstrapQ.msg({
									msg : "删除临时保存内容成功 :" + (new Date()).toLocaleDateString(),
									type : 'success',
									time : 1000,
						});
	                }else {
	                    //bootstrapQ.alert("临时保存失败:"+ret.msgInfo);
	                    bootstrapQ.msg({
							msg : "删除临时保存内容失败 :" + +ret.msgInfo,
							type : 'danger',
							time : 1000,
						});
	                }
	            },
	            error : function () {
	                //bootstrapQ.alert("临时保存异常");
	                bootstrapQ.msg({
						msg : "删除临时保存内容失败 :服务器异常",
						type : 'danger',
						time : 1000,
					});
	            }
	        });

	    }

		//开放给外部的调用接口
        return {
            loadmd: function(content){
               loadmd(content);
            },
            editorSwitch: function(edit){
            	editorSwitch(edit);
            },
            startAutoTmpSaver: function(){
            	startAutoTmpSaver();
            },
            stopAutoTmpSaver: function(){
            	stopAutoTmpSaver();
            },
            
            deleteTmpSavedContent: function(docId){
            	deleteTmpSavedContent(docId);
            },
            
        };
    })();