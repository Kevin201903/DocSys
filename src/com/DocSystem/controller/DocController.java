package com.DocSystem.controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;

import util.FileUtils2;
import util.GsonUtils;
import util.LuceneUtil2;
import util.ReadProperties;
import util.ReturnAjax;
import util.DocConvertUtil.Office2PDF;
import util.SvnUtil.SVNUtil;

import com.DocSystem.entity.Doc;
import com.DocSystem.entity.DocAuth;
import com.DocSystem.entity.LogEntry;
import com.DocSystem.entity.Repos;
import com.DocSystem.entity.User;
import com.DocSystem.service.impl.ReposServiceImpl;
import com.DocSystem.service.impl.UserServiceImpl;
import com.DocSystem.controller.BaseController;
import com.alibaba.fastjson.JSONObject;

/*
 Something you need to know
 1、文件节点增、删、改
（1）文件节点可以是文件或目录（包括实文件和虚文件）
（2）实文件节点包括三部分内容：本地文件、版本仓库文件、数据库记录
（3）虚文件节点包括三部分内容：本地目录、版本仓库目录、数据库记录
	虚文件的实体跟实文件不同，并不是一个单一的文件，而是以文件节点ID为名称的目录，里面包括content.md文件和res目录，markdown文件记录了虚文件的文字内容，res目录下存放相关的资源文件
（4）add、delete、edit是文件节点的基本功能， rename、move、copy、upload是基本功能的扩展功能
（5）文件节点操作总是原子操作
	这个主要是针对upload和copy接口而言，
	前台上传多个文件和目录时，实际上也是一个文件一个文件上传的，而不是一起上传到后台后再做处理的，这是为了保证前后台信息一致，这样前台能够知道每一个文件节点的更新情况
	前台执行目录复制操作的话，也是同样一层层目录复制
（6）文件节点信息的更新优先次序依次为 本地文件、版本仓库文件、数据库记录
	版本仓库文件如果更新失败，则本地文件需要回退，以保证本地文件与版本仓库最新版本的文件一致
	数据库记录更新失败时，本地文件和版本仓库文件不会进行回退操作，这里面有些风险但还可以接受（主要是add和delete操作的一些后遗症）
2、路径定义规则
(1) 仓库路径
 reposPath: 仓库根路径，以"/"结尾
 reposRPath: 仓库实文件存储根路径,reposPath + "data/rdata/"
 reposVPath: 仓库虚文件存储根路径,reposPath + "data/vdata/"
 reposRefRPath: 仓库实文件存储根路径,reposPath + "refData/rdata/"
 reposRefVPath: 仓库虚文件存储根路径,reposPath + "refData/vdata/"
 reposUserTempPath: 仓库虚文件存储根路径,reposPath + "tmp/userId/" 
(2) parentPath: 该变量通过getParentPath获取，如果是文件则获取的是其父节点的目录路径，如果是目录则获取到的是目录路径，以空格开头，以"/"结尾
(3) 文件/目录相对路径: docRPath = parentPath + doc.name docVName = HashValue(docRPath)  末尾不带"/"
(4) 文件/目录本地全路径: localDocRPath = reposRPath + parentPath + doc.name  localVDocPath = repoVPath + HashValue(docRPath) 末尾不带"/"
 */
@Controller
@RequestMapping("/Doc")
public class DocController extends BaseController{
	@Autowired
	private ReposServiceImpl reposService;
	@Autowired
	private UserServiceImpl userService;
	//线程锁
	private static final Object syncLock = new Object(); 
	
	/*******************************  Ajax Interfaces For Document Controller ************************/ 
	/****************   add a Document ******************/
	@RequestMapping("/addDoc.do")  //文件名、文件类型、所在仓库、父节点
	public void addDoc(String name,String content,Integer type,Integer reposId,Integer parentId,String commitMsg,HttpSession session,HttpServletRequest request,HttpServletResponse response){
		System.out.println("addDoc name: " + name + " type: " + type+ " reposId: " + reposId + " parentId: " + parentId + " content: " + content);
		//System.out.println(Charset.defaultCharset());
		//System.out.println("黄谦");
		
		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		String commitUser = login_user.getName();
		
		//检查用户是否有权限新增文件
		if(checkUserAddRight(rt,login_user.getId(),parentId,reposId) == false)
		{
			writeJson(rt, response);	
			return;
		}
		
		if(commitMsg == null)
		{
			commitMsg = "addDoc " + name;
		}
		Integer docId = addDoc(name,content,type,null,0,"",reposId,parentId,null,null,null,commitMsg,commitUser,login_user,rt);
		
		writeJson(rt, response);
		
		if("ok".equals(rt.getStatus()))
		{
			//Add Lucene Index For Vdoc
			addIndexForVDoc(docId,content);
		}
	}
	
	//Add Index For VDoc
	private void addIndexForVDoc(Integer docId, String content) {
		if(content == null || "".equals(content))
		{
			return;
		}
		
		try {
			System.out.println("addIndexForVDoc() add index in lucne: docId " + docId + " content:" + content);
			//Add Index For Content
			LuceneUtil2.addIndex(docId + "-0", docId,content, "doc");
		} catch (Exception e) {
			System.out.println("addIndexForVDoc() Failed to update lucene Index");
			e.printStackTrace();
		}
	}
	/****************   Feeback  ******************/
	@RequestMapping("/feeback.do")
	public void addDoc(String name,String content, HttpSession session,HttpServletRequest request,HttpServletResponse response){
		System.out.println("feeback name: " + name + " content: " + content);

		ReturnAjax rt = new ReturnAjax();
		String commitUser = "游客";
		User login_user = (User) session.getAttribute("login_user");
		if(login_user != null)
		{
			commitUser = login_user.getName();
		}
		else
		{
			login_user = new User();
			login_user.setId(0);
		}
		Integer reposId = getReposIdForFeeback();		
		Integer parentId = getParentIdForFeeback();
		
		String commitMsg = "User Feeback by " + name;
		Integer docId = addDoc(name,content,1,null,0,"",reposId,parentId,null,null,null,commitMsg,commitUser,login_user,rt);
		
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Access-Control-Allow-Methods", " GET,POST,OPTIONS,HEAD");
		response.setHeader("Access-Control-Allow-Headers", "Content-Type,Accept,Authorization");
		response.setHeader("Access-Control-Expose-Headers", "Set-Cookie");		
		
		writeJson(rt, response);
		
		if("ok".equals(rt.getStatus()))
		{
			//Add Lucene Index For Vdoc
			addIndexForVDoc(docId,content);
		}
	}
	
	private Integer getReposIdForFeeback() {
		String tempStr = null;
		tempStr = ReadProperties.read("docSysConfig.properties", "feebackReposId");
	    if(tempStr == null || "".equals(tempStr))
	    {
	    	return 5;
	    }
	    
	    return(Integer.parseInt(tempStr));
	}

	private Integer getParentIdForFeeback() {
		String tempStr = null;
		tempStr = ReadProperties.read("docSysConfig.properties", "feebackParentId");
	    if(tempStr == null || "".equals(tempStr))
	    {
	    	return 0;
	    }

	    return(Integer.parseInt(tempStr));
 	}

	/****************   delete a Document ******************/
	@RequestMapping("/deleteDoc.do")
	public void deleteDoc(Integer id,String commitMsg,HttpSession session,HttpServletRequest request,HttpServletResponse response){
		System.out.println("deleteDoc id: " + id);

		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		String commitUser = login_user.getName();
		
		//get doc
		Doc doc = reposService.getDocInfo(id);
		if(doc == null)
		{
			rt.setError("文件不存在！");
			writeJson(rt, response);			
			return;			
		}
		
		//获取仓库信息
		Integer reposId = doc.getVid();
		Integer parentId = doc.getPid();
		//检查用户是否有权限新增文件
		if(checkUserDeleteRight(rt,login_user.getId(),parentId,reposId) == false)
		{
			writeJson(rt, response);	
			return;
		}
		
		if(commitMsg == null)
		{
			commitMsg = "deleteDoc " + doc.getName();
		}
		deleteDoc(id,reposId, parentId, commitMsg, commitUser, login_user, rt);
		writeJson(rt, response);	
		
		if("ok".equals(rt.getStatus()))
		{
			//Delete Lucene index For Doc
			try {
				System.out.println("DeleteDoc() delete index in lucne: docId " + id);
				LuceneUtil2.deleteIndexForDoc(id,"doc");
			} catch (Exception e) {
				System.out.println("DeleteDoc() Failed to delete lucene Index");
				e.printStackTrace();
			}
		}
	}
	/****************   Check a Document ******************/
	@RequestMapping("/checkChunkUploaded.do")
	public void checkChunkUploaded(String name,Integer docId,  Integer size, String checkSum,Integer chunkIndex,Integer chunkNum,Integer cutSize,Integer chunkSize,String chunkHash,Integer reposId,Integer parentId,String commitMsg,HttpSession session,HttpServletRequest request,HttpServletResponse response){
		System.out.println("checkChunkUploaded name: " + name + " size: " + size + " checkSum: " + checkSum + " chunkIndex: " + chunkIndex + " chunkNum: " + chunkNum + " cutSize: " + cutSize+ " chunkSize: " + chunkSize+ " chunkHash: " + chunkHash+ " reposId: " + reposId + " parentId: " + parentId);
		ReturnAjax rt = new ReturnAjax();

		User login_user = (User) session.getAttribute("login_user");
		
		if("".equals(checkSum))
		{
			//CheckSum is empty, mean no need 
			writeJson(rt, response);
			return;
		}
		

		//判断tmp目录下是否有分片文件，并且checkSum和size是否相同 
		rt.setMsgData("0");
		String fileChunkName = name + "_" + chunkIndex;
		Repos repos = reposService.getRepos(reposId);
		String userTmpDir = getReposUserTmpPath(repos,login_user);
		String chunkParentPath = userTmpDir;
		String chunkFilePath = chunkParentPath + fileChunkName;
		if(true == isChunkMatched(chunkFilePath,chunkHash))
		{
			rt.setMsgInfo("chunk: " + fileChunkName +" 已存在，且checkSum相同！");
			rt.setMsgData("1");
			
			System.out.println("checkChunkUploaded() " + fileChunkName + " 已存在，且checkSum相同！");
			if(chunkIndex == chunkNum -1)	//It is the last chunk
			{
				if(commitMsg == null)
				{
					commitMsg = "uploadDoc " + name;
				}
				String commitUser = login_user.getName();
				if(-1 == docId)	//新建文件则新建记录，否则
				{
					docId = addDoc(name,null, 1, null,size, checkSum,reposId, parentId, chunkNum, cutSize, chunkParentPath,commitMsg, commitUser,login_user, rt);
				}
				else
				{
					updateDoc(docId, null, size,checkSum, reposId, parentId, chunkNum, cutSize, chunkParentPath, commitMsg, commitUser, login_user, rt);
				}
				writeJson(rt, response);
				
				if("ok".equals(rt.getStatus()))
				{
					//Update Index For RDoc
					String parentPath = getParentPath(parentId);
					String reposRPath = getReposRealPath(repos);
					String localDocRPath = reposRPath + parentPath + name;
					updatIndexForRDoc(docId, localDocRPath);
					
					//Delete All Trunks if trunks have been combined
					deleteChunks(name,chunkIndex, chunkNum,chunkParentPath);
				}
				return;
			}
		}
		writeJson(rt, response);
	}
	
	private String combineChunks(String targetParentPath,String fileName, Integer chunkNum,Integer cutSize, String chunkParentPath) {
		try {
			String targetFilePath = targetParentPath + fileName;
			FileOutputStream out;

			out = new FileOutputStream(targetFilePath);
	        FileChannel outputChannel = out.getChannel();   

        	long offset = 0;
	        for(int chunkIndex = 0; chunkIndex < chunkNum; chunkIndex ++)
	        {
	        	String chunkFilePath = chunkParentPath + fileName + "_" + chunkIndex;
	        	FileInputStream in=new FileInputStream(chunkFilePath);
	            FileChannel inputChannel = in.getChannel();    
	            outputChannel.transferFrom(inputChannel, offset, inputChannel.size());
	        	offset += inputChannel.size();	        			
	    	   	inputChannel.close();
	    	   	in.close();
	    	}
	        outputChannel.close();
		    out.close();
		    return fileName;
		} catch (Exception e) {
			System.out.println("combineChunks() Failed to combine the chunks");
			e.printStackTrace();
			return null;
		}        
	}
	
	private void deleteChunks(String name, Integer chunkIndex, Integer chunkNum, String chunkParentPath) {
		System.out.println("deleteChunks() name:" + name + " chunkIndex:" + chunkIndex  + " chunkNum:" + chunkNum + " chunkParentPath:" + chunkParentPath);
		
		if(null == chunkIndex || chunkIndex < (chunkNum-1))
		{
			return;
		}
		
		System.out.println("deleteChunks() name:" + name + " chunkIndex:" + chunkIndex  + " chunkNum:" + chunkNum + " chunkParentPath:" + chunkParentPath);
		try {
	        for(int i = 0; i < chunkNum; i ++)
	        {
	        	String chunkFilePath = chunkParentPath + name + "_" + i;
	        	deleteFile(chunkFilePath);
	    	}
		} catch (Exception e) {
			System.out.println("deleteChunks() Failed to combine the chunks");
			e.printStackTrace();
		}  
	}

	private boolean isChunkMatched(String chunkFilePath, String chunkHash) {
		//检查文件是否存在
		File f = new File(chunkFilePath);
		if(!f.exists()){
			return false;
		}

		//Check if chunkHash is same
		try {
			FileInputStream file = new FileInputStream(chunkFilePath);
			String hash=DigestUtils.md5Hex(file);
			file.close();
			if(hash.equals(chunkHash))
			{
				return true;
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}

		return false;
	}

	/****************   Check a Document ******************/
	@RequestMapping("/checkDocInfo.do")
	public void checkDocInfo(String name,Integer type,Integer size,String checkSum,Integer reposId,Integer parentId,String commitMsg,HttpSession session,HttpServletRequest request,HttpServletResponse response){
		System.out.println("checkDocInfo name: " + name + " type: " + type + " size: " + size + " checkSum: " + checkSum+ " reposId: " + reposId + " parentId: " + parentId);
		ReturnAjax rt = new ReturnAjax();

		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		
		//检查登录用户的权限
		DocAuth UserDocAuth = getUserDocAuth(login_user.getId(),parentId,reposId);
		if(UserDocAuth == null)
		{
			rt.setError("您无权在该目录上传文件!");
			writeJson(rt, response);
			return;
		}
		else 
		{			
			//Get File Size 
			Integer MaxFileSize = getMaxFileSize();	//获取系统最大文件限制
			if(MaxFileSize != null)
			{
				if(size > MaxFileSize.longValue()*1024*1024)
				{
					rt.setError("上传文件超过 "+ MaxFileSize + "M");
					writeJson(rt, response);
					return;
				}
			}
			
			//任意用户文件不得30M
			if((UserDocAuth.getGroupId() == null) && ((UserDocAuth.getUserId() == null) || (UserDocAuth.getUserId() == 0)))
			{
				if(size > 30*1024*1024)
				{
					rt.setError("非仓库授权用户最大上传文件不超过30M!");
					writeJson(rt, response);
					return;
				}
			}
		}
		
		if("".equals(checkSum))
		{
			//CheckSum is empty, mean no need 
			writeJson(rt, response);
			return;
		}
		
		//判断目录下是否有同名节点 
		Doc doc = getDocByName(name,parentId,reposId);
		if(doc != null)
		{
			rt.setData(doc);
			rt.setMsgInfo("Node: " + name +" 已存在！");
			rt.setMsgData("0");
			System.out.println("checkDocInfo() " + name + " 已存在");
	
			//检查checkSum是否相同
			if(type == 1)
			{
				if(true == isDocCheckSumMatched(doc,size,checkSum))
				{
					rt.setMsgInfo("Node: " + name +" 已存在，且checkSum相同！");
					rt.setMsgData("1");
					System.out.println("checkDocInfo() " + name + " 已存在，且checkSum相同！");
				}
			}
			writeJson(rt, response);
			return;
		}
		else
		{
			if(size > 1024)	//小于1K的文件没有必要
			{
				//Try to find the same Doc in the repos
				Doc sameDoc = getSameDoc(size,checkSum,reposId);
				if(null != sameDoc)
				{
					System.out.println("checkDocInfo() " + sameDoc.getName() + " found！");
					//Do copy the Doc
					copyDoc(sameDoc.getId(),sameDoc.getName(),name,sameDoc.getType(),reposId,sameDoc.getPid(),parentId,commitMsg,login_user.getName(),login_user,rt);
					Doc newDoc = getDocByName(name,parentId,reposId);
					if(null != newDoc)
					{
						System.out.println("checkDocInfo() " + sameDoc.getName() + " was copied ok！");
						rt.setData(newDoc);
						rt.setMsgInfo("SameDoc " + sameDoc.getName() +" found and do copy OK！");
						rt.setMsgData("1");
						writeJson(rt, response);
						return;
					}
				}
			}
		}
		
		writeJson(rt, response);
	}
	
	private Doc getSameDoc(Integer size, String checkSum, Integer reposId) {

		Doc qdoc = new Doc();
		qdoc.setSize(size);
		qdoc.setCheckSum(checkSum);
		qdoc.setVid(reposId);
		List <Doc> docList = reposService.getDocList(qdoc);
		if(docList != null && docList.size() > 0)
		{
			return docList.get(0);
		}
		return null;
	}

	private boolean isDocCheckSumMatched(Doc doc,Integer size, String checkSum) {
		System.out.println("isDocCheckSumMatched() size:" + size + " checkSum:" + checkSum + " docSize:" + doc.getSize() + " docCheckSum:"+doc.getCheckSum());
		if(size.equals(doc.getSize()) && !"".equals(checkSum) && checkSum.equals(doc.getCheckSum()))
		{
			return true;
		}
		return false;
	}

	/****************   Upload a Document ******************/
	@RequestMapping("/uploadDoc.do")
	public void uploadDoc(MultipartFile uploadFile,String name,Integer size, String checkSum, Integer reposId, Integer parentId, Integer docId, String filePath,
			Integer chunkIndex, Integer chunkNum, Integer cutSize, Integer chunkSize, String chunkHash,
			String commitMsg,HttpServletResponse response,HttpServletRequest request,HttpSession session) throws Exception{
		System.out.println("uploadDoc name " + name + " size:" +size+ " checkSum:" + checkSum + " reposId:" + reposId + " parentId:" + parentId  + " docId:" + docId + " filePath:" + filePath 
							+ " chunkIndex:" + chunkIndex + " chunkNum:" + chunkNum + " cutSize:" + cutSize  + " chunkSize:" + chunkSize + " chunkHash:" + chunkHash);

		ReturnAjax rt = new ReturnAjax();

		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		String commitUser = login_user.getName();
		
		if(null == docId)
		{
			rt.setError("异常请求，docId是空！");
			writeJson(rt, response);			
			return;
		}
		
		//检查用户是否有权限新增文件
		if(-1 == docId)
		{
			if(checkUserAddRight(rt,login_user.getId(),parentId,reposId) == false)
			{
				writeJson(rt, response);	
				return;
			}
		}
		else
		{
			if(checkUserEditRight(rt,login_user.getId(),docId,reposId) == false)
			{
				writeJson(rt, response);	
				return;
			}
		}
		
		Repos repos = reposService.getRepos(reposId);

		//如果是分片文件，则保存分片文件
		if(null != chunkIndex)
		{
			//Save File chunk to tmp dir with name_chunkIndex
			String fileChunkName = name + "_" + chunkIndex;
			String userTmpDir = getReposUserTmpPath(repos,login_user);
			if(saveFile(uploadFile,userTmpDir,fileChunkName) == null)
			{
				rt.setError("分片文件 " + fileChunkName +  " 暂存失败!");
				writeJson(rt, response);
				return;
			}
			
			if(chunkIndex < (chunkNum-1))
			{
				rt.setData(chunkIndex);	//Return the sunccess upload chunkIndex
				writeJson(rt, response);
				return;
				
			}
		}
		
		//非分片上传或LastChunk Received
		if(uploadFile != null) 
		{
			String fileName = name;
			String chunkParentPath = getReposUserTmpPath(repos,login_user);
			if(commitMsg == null)
			{
				commitMsg = "uploadDoc " + fileName;
			}
			if(-1 == docId)	//新建文件则新建记录，否则
			{
				docId = addDoc(name,null, 1, uploadFile,size, checkSum,reposId, parentId, chunkNum, chunkSize, chunkParentPath,commitMsg, commitUser, login_user, rt);
			}
			else
			{
				updateDoc(docId, uploadFile, size,checkSum, reposId, parentId, chunkNum, chunkSize, chunkParentPath,commitMsg, commitUser, login_user, rt);
			}
			writeJson(rt, response);
			
			if("ok".equals(rt.getStatus()))
			{
				//UpdatIndexForRDoc
				String parentPath = getParentPath(parentId);
				String reposRPath = getReposRealPath(repos);
				String localDocRPath = reposRPath + parentPath + name;
				updatIndexForRDoc(docId,localDocRPath);
				
				//Delete All Trunks if trunks have been combined
				deleteChunks(name,chunkIndex,chunkNum,chunkParentPath);
			}
			return;
		}
		else
		{
			rt.setError("文件上传失败！");
		}
		writeJson(rt, response);
	}

	private void updatIndexForRDoc(Integer docId, String localDocRPath) {
		//Add the doc to lucene Index
		try {
			System.out.println("updatIndexForRDoc() add index in lucne: docId " + docId);
			//Add Index For File
			LuceneUtil2.updateIndexForRDoc(docId,localDocRPath, "doc");
		} catch (Exception e) {
			System.out.println("updatIndexForRDoc() Failed to update lucene Index");
			e.printStackTrace();
		}
	}

	/****************   Upload a Picture for Markdown ******************/
	@RequestMapping("/uploadMarkdownPic.do")
	public void uploadMarkdownPic(@RequestParam(value = "editormd-image-file", required = true) MultipartFile file, HttpServletRequest request,HttpServletResponse response,HttpSession session) throws Exception{
		System.out.println("uploadMarkdownPic ");
		
		JSONObject res = new JSONObject();

		//Get the currentDocId from Session which was set in getDocContent
		Integer docId = (Integer) session.getAttribute("currentDocId");
		if(docId == null || docId == 0)
		{
			res.put("success", 0);
			res.put("message", "upload failed: currentDocId was not set!");
			writeJson(res,response);
			return;
		}
		
		Doc doc = reposService.getDoc(docId);
		if(doc == null)
		{
			res.put("success", 0);
			res.put("message", "upload failed: getDoc failed for docId:" + docId );
			writeJson(res,response);
			return;			
		}
				
		//MayBe We need to save current Edit docId in session, So that I can put the pic to dedicated VDoc Directory
		if(file == null) 
		{
			res.put("success", 0);
			res.put("message", "upload failed: file is null!");
			writeJson(res,response);
			return;
		}
		
		//Save the file
		String fileName =  file.getOriginalFilename();

		
		//get localParentPath for Markdown Img
		//String localParentPath = getWebTmpPath() + "markdownImg/";
		Repos repos = reposService.getRepos(doc.getVid());
		String reposVPath = getReposVirtualPath(repos);
		String docVName = getDocVPath(doc);
		String localVDocPath = reposVPath + docVName;
		String localParentPath = localVDocPath + "/res/";
		
		//Check and create localParentPath
		File dir = new File(localParentPath);
		if(!dir.exists())	
		{
			dir.mkdirs();
		}
		
		String retName = saveFile(file, localParentPath,fileName);
		if(retName == null)
		{
			res.put("success", 0);
			res.put("message", "upload failed: saveFile error!");
			writeJson(res,response);
			return;
		}
		
		//res.put("url", "/DocSystem/tmp/markdownImg/"+fileName);
		res.put("url", "/DocSystem/Doc/getVDocRes.do?docId="+docId+"&fileName="+fileName);
		res.put("success", 1);
		res.put("message", "upload success!");
		writeJson(res,response);
	}

	/****************   rename a Document ******************/
	@RequestMapping("/renameDoc.do")
	public void renameDoc(Integer id,String newname, String commitMsg,HttpSession session,HttpServletRequest request,HttpServletResponse response){
		System.out.println("renameDoc id: " + id + " newname: " + newname);
		
		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		String commitUser = login_user.getName();
		
		//get doc
		Doc doc = reposService.getDocInfo(id);
		if(doc == null)
		{
			rt.setError("文件不存在！");
			writeJson(rt, response);			
			return;			
		}
		
		//检查用户是否有权限编辑文件
		if(checkUserEditRight(rt,login_user.getId(),id,doc.getVid()) == false)
		{
			writeJson(rt, response);	
			return;
		}
		
		//开始更改名字了
		Integer reposId = doc.getVid();
		Integer parentId = doc.getPid();
		
		if(commitMsg == null)
		{
			commitMsg = "renameDoc " + doc.getName();
		}
		renameDoc(id,newname,reposId,parentId,commitMsg,commitUser,login_user,rt);
		writeJson(rt, response);	
	}
	

	
	/****************   move a Document ******************/
	@RequestMapping("/moveDoc.do")
	public void moveDoc(Integer id,Integer dstPid,Integer vid,String commitMsg,HttpSession session,HttpServletRequest request,HttpServletResponse response){
		System.out.println("moveDoc id: " + id + " dstPid: " + dstPid + " vid: " + vid);
		
		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		String commitUser = login_user.getName();
		
		Doc doc = reposService.getDocInfo(id);
		if(doc == null)
		{
			rt.setError("文件不存在");
			writeJson(rt, response);	
			return;			
		}
	
		//检查是否有源目录的删除权限
		if(checkUserDeleteRight(rt,login_user.getId(),doc.getPid(),vid) == false)
		{
			writeJson(rt, response);	
			return;
		}
	
		//检查用户是否有目标目录权限新增文件
		if(checkUserAddRight(rt,login_user.getId(),dstPid,vid) == false)
		{
				writeJson(rt, response);	
				return;
		}
		
		//开始移动了
		if(commitMsg == null)
		{
			commitMsg = "moveDoc " + doc.getName();
		}
		moveDoc(id,vid,doc.getPid(),dstPid,commitMsg,commitUser,login_user,rt);		
		writeJson(rt, response);	
	}
	
	/****************   move a Document ******************/
	@RequestMapping("/copyDoc.do")
	public void copyDoc(Integer id,Integer dstPid, String dstDocName, Integer vid,String commitMsg,HttpSession session,HttpServletRequest request,HttpServletResponse response){
		System.out.println("copyDoc id: " + id  + " dstPid: " + dstPid + " dstDocName: " + dstDocName + " vid: " + vid);
		
		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		String commitUser = login_user.getName();
		
		Doc doc = reposService.getDocInfo(id);
		if(doc == null)
		{
			rt.setError("文件不存在");
			writeJson(rt, response);	
			return;			
		}
	
		//检查用户是否有目标目录权限新增文件
		if(checkUserAddRight(rt,login_user.getId(),dstPid,vid) == false)
		{
			writeJson(rt, response);
			return;
		}
		
		String srcDocName = doc.getName();
		if(dstDocName == null || "".equals(dstDocName))
		{
			dstDocName = srcDocName;
		}
		
		if(commitMsg == null)
		{
			commitMsg = "copyDoc " + doc.getName() + " to " + dstDocName;
		}
		copyDoc(id,srcDocName,dstDocName,doc.getType(),vid,doc.getPid(),dstPid,commitMsg,commitUser,login_user,rt);
		writeJson(rt, response);
	}

	/****************   update Document Content: This interface was triggered by save operation by user ******************/
	@RequestMapping("/updateDocContent.do")
	public void updateDocContent(Integer id,String content,String commitMsg,HttpSession session,HttpServletRequest request,HttpServletResponse response){
		System.out.println("updateDocContent id: " + id);
		
		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		String commitUser = login_user.getName();
		
		Doc doc = reposService.getDocInfo(id);
		if(doc == null)
		{
			rt.setError("文件不存在");
			writeJson(rt, response);			
			return;
		}
		
		//检查用户是否有权限编辑文件
		if(checkUserEditRight(rt,login_user.getId(),id,doc.getVid()) == false)
		{
			writeJson(rt, response);	
			return;
		}
		
		if(commitMsg == null)
		{
			commitMsg = "updateDocContent " + doc.getName();
		}
		updateDocContent(id, content, commitMsg, commitUser, login_user, rt);
		writeJson(rt, response);
		
		if("ok".equals(rt.getStatus()))
		{
			//Delete tmp saved doc content
			Repos repos = reposService.getRepos(doc.getVid());
			String docVName = getDocVPath(doc);
			String userTmpDir = getReposUserTmpPath(repos,login_user);
			delFileOrDir(userTmpDir+docVName);
			
			//Update Index For VDoc
			try {
				System.out.println("UpdateDocContent() updateIndexForVDoc in lucene: docId " + id);
				LuceneUtil2.updateIndexForVDoc(id,content,"doc");
			} catch (Exception e) {
				System.out.println("UpdateDocContent() Failed to update lucene Index");
				e.printStackTrace();
			}
		}
	}

	//this interface is for auto save of the virtual doc edit
	@RequestMapping("/tmpSaveDocContent.do")
	public void tmpSaveVirtualDocContent(Integer id,String content,HttpSession session,HttpServletRequest request,HttpServletResponse response){
		System.out.println("tmpSaveVirtualDocContent() id: " + id);
		
		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}

		Doc doc = reposService.getDocInfo(id);
		if(doc == null)
		{
			rt.setError("文件不存在");
			writeJson(rt, response);			
			return;
		}
		
		//检查用户是否有权限编辑文件
		if(checkUserEditRight(rt,login_user.getId(),id,doc.getVid()) == false)
		{
			writeJson(rt, response);	
			return;
		}
		
		Repos repos = reposService.getRepos(doc.getVid());
		String docVName = getDocVPath(doc);
		//Save the content to virtual file
		String userTmpDir = getReposUserTmpPath(repos,login_user);
		
		if(saveVirtualDocContent(userTmpDir,docVName,content,rt) == false)
		{
			rt.setError("saveVirtualDocContent Error!");
		}
		writeJson(rt, response);
	}
	
	/**************** download Doc  ******************/
	@RequestMapping("/doGet.do")
	public void doGet(Integer id,HttpServletResponse response,HttpServletRequest request,HttpSession session) throws Exception{
		System.out.println("doGet id: " + id);

		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		
		Doc doc = reposService.getDoc(id);
		if(doc==null){
			System.out.println("doGet() Doc " + id + " 不存在");
			rt.setError("doc " + id + "不存在！");
			writeJson(rt, response);
			return;
		}
		
		//得到要下载的文件名
		String file_name = doc.getName();
		
		//虚拟文件下载
		Repos repos = reposService.getRepos(doc.getVid());
		//虚拟文件系统下载，直接将数据库的文件内容传回去，未来需要优化
		if(isRealFS(repos.getType()) == false)
		{
			String content = doc.getContent();
			byte [] data = content.getBytes();
			sendDataToWebPage(file_name,data, response, request); 
			return;
		}
		
		//get reposRPath
		String reposRPath = getReposRealPath(repos);
				
		//get srcParentPath
		String srcParentPath = getParentPath(doc.getPid());	//doc的ParentPath

		//文件的localParentPath
		String localParentPath = reposRPath + srcParentPath;
		System.out.println("doGet() localParentPath:" + localParentPath);
		
		//get userTmpDir
		String userTmpDir = getReposUserTmpPath(repos,login_user);
		
		sendTargetToWebPage(localParentPath,file_name, userTmpDir, rt, response, request);
	}
	
	@RequestMapping("/getVDocRes.do")
	public void getVDocRes(Integer docId,String fileName,HttpServletResponse response,HttpServletRequest request,HttpSession session) throws Exception{
		System.out.println("getVDocRes docId:" + docId + " fileName: " + fileName);

		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		
		Doc doc = reposService.getDoc(docId);
		if(doc==null){
			System.out.println("doGet() Doc " + docId + " 不存在");
			rt.setError("doc " + docId + "不存在！");
			writeJson(rt, response);
			return;
		}
		
		//Get the file
		Repos repos = reposService.getRepos(doc.getVid());
		String reposVPath = getReposVirtualPath(repos);
		String docVName = getDocVPath(doc);
		String localVDocPath = reposVPath + docVName;
		String localParentPath = localVDocPath + "/res/";		
		System.out.println("getVDocRes() localParentPath:" + localParentPath);
		
		sendFileToWebPage(localParentPath,fileName, rt, response, request);
	}
	
	/**************** get Tmp File ******************/
	@RequestMapping("/doGetTmpFile.do")
	public void doGetTmp(Integer reposId,String parentPath, String fileName,HttpServletResponse response,HttpServletRequest request,HttpSession session) throws Exception{
		System.out.println("doGetTmpFile reposId: " + reposId);

		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		
		//虚拟文件下载
		Repos repos = reposService.getRepos(reposId);
		
		//get userTmpDir
		String userTmpDir = getReposUserTmpPath(repos,login_user);
		
		String localParentPath = userTmpDir;
		if(parentPath != null)
		{
			localParentPath = userTmpDir + parentPath;
		}
		
		sendFileToWebPage(localParentPath,fileName,rt, response, request); 
	}

	/**************** download History Doc  ******************/
	@RequestMapping("/getHistoryDoc.do")
	public void getHistoryDoc(long revision,Integer reposId, String parentPath, String docName, HttpServletResponse response,HttpServletRequest request,HttpSession session) throws Exception{
		System.out.println("getHistoryDoc revision: " + revision + " reposId:" + reposId + " parentPath:" + parentPath + " docName:" + docName);

		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		
		//get reposInfo to 
		Repos repos = reposService.getRepos(reposId);
		
		//URL was encode by EncodeURI, so just decode it here
		docName = new String(docName.getBytes("ISO8859-1"),"UTF-8");  
		parentPath = new String(parentPath.getBytes("ISO8859-1"),"UTF-8");  
		System.out.println("getHistoryDoc() docName:" + docName + " parentPath:" + parentPath);
		
		//userTmpDir will be used to tmp store the history doc 
		String userTmpDir = getReposUserTmpPath(repos,login_user);
		
		String targetName = docName + "_" + revision;
		//If the docName is "" means we are checking out the root dir of repos, so we take the reposName as the targetName
		if("".equals(docName))
		{
			targetName = repos.getName() + "_" + revision;
		}
		
		//checkout the entry to local
		String reposURL = repos.getSvnPath();
		String svnUser = repos.getSvnUser();
		String svnPwd = repos.getSvnPwd();
		if(svnCheckOut(reposURL, svnUser, svnPwd, parentPath, docName, userTmpDir, targetName, revision) == false)
		{
			System.out.println("getHistoryDoc() svnCheckOut Failed!");
			rt.setError("svnCheckOut Failed parentPath:" + parentPath + " docName:" + docName + " userTmpDir:" + userTmpDir + " targetName:" + targetName);
			writeJson(rt, response);	
			return;
		}
		
		sendTargetToWebPage(userTmpDir, targetName, userTmpDir, rt, response, request);
		
		//delete the history file or dir
		delFileOrDir(userTmpDir+targetName);
	}

	/**************** convert Doc To PDF ******************/
	@RequestMapping("/DocToPDF.do")
	public void DocToPDF(Integer docId,HttpServletResponse response,HttpServletRequest request,HttpSession session) throws Exception{
		System.out.println("DocToPDF docId: " + docId);

		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		
		Doc doc = reposService.getDoc(docId);
		if(doc == null)
		{
			rt.setError("文件不存在");
			writeJson(rt, response);	
			return;			
		}
		
		//检查用户是否有文件读取权限
		if(checkUseAccessRight(rt,login_user.getId(),docId,doc.getVid()) == false)
		{
			System.out.println("DocToPDF() you have no access right on doc:" + docId);
			writeJson(rt, response);	
			return;
		}
		
		if(doc.getType() == 2)
		{
			rt.setError("目录无法预览");
			writeJson(rt, response);
			return;
		}
		
		//虚拟文件下载
		Repos repos = reposService.getRepos(doc.getVid());
				
		//get reposRPath
		String reposRPath = getReposRealPath(repos);
				
		//get srcParentPath
		String srcParentPath = getParentPath(docId);	//文件或目录的相对路径
		//文件的真实全路径
		String srcPath = reposRPath + srcParentPath;
		srcPath = srcPath + doc.getName();			
		System.out.println("DocToPDF() srcPath:" + srcPath);
	
		String webTmpPath = getWebTmpPath();
		String dstName = doc.getCheckSum() + ".pdf";
		if(doc.getCheckSum() == null)
		{
			dstName = doc.getName();
		}
		String dstPath = webTmpPath + "preview/" + dstName;
		System.out.println("DocToPDF() dstPath:" + dstPath);
		File file = new File(dstPath);
		if(!file.exists())
		{
			if(srcPath.endsWith(".pdf"))
			{
				FileUtils2.copyFile(srcPath, dstPath);
			}
			else
			{
				String fileType = FileUtils2.getFileType(srcPath);
				if(fileType != null && fileType == "pdf")
				{
					FileUtils2.copyFile(srcPath, dstPath);
				}
				else
				{
					File pdf = Office2PDF.openOfficeToPDF(srcPath,dstPath);
					if(pdf == null)
					{
						rt.setError("Failed to convert office to pdf");
						rt.setMsgData("srcPath:"+srcPath);
						writeJson(rt, response);
						return;
					}
				}
			}
		}
		//Save the pdf to web
		String fileLink = "/DocSystem/tmp/preview/" + dstName;
		rt.setData(fileLink);
		writeJson(rt, response);
	}

	/****************   get Document Content ******************/
	@RequestMapping("/getDocContent.do")
	public void getDocContent(Integer id,HttpServletRequest request,HttpServletResponse response,HttpSession session){
		System.out.println("getDocContent id: " + id);
		
		ReturnAjax rt = new ReturnAjax();
		
		Doc doc = reposService.getDoc(id);
		rt.setData(doc.getContent());
		//System.out.println(rt.getData());

		writeJson(rt, response);
	}
	
	/****************   get Document Info ******************/
	@RequestMapping("/getDoc.do")
	public void getDoc(Integer id,HttpSession session,HttpServletRequest request,HttpServletResponse response){
		System.out.println("getDoc id: " + id);
		ReturnAjax rt = new ReturnAjax();
		
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		
		Doc doc = reposService.getDoc(id);
		if(doc == null)
		{
			rt.setError("文件不存在");
			writeJson(rt, response);	
			return;			
		}
		
		//Set currentDocId to session which will be used MarkDown ImgUpload
		session.setAttribute("currentDocId", id);
		System.out.println("getDoc currentDocId:" + id);
	
		//检查用户是否有文件读取权限
		if(checkUseAccessRight(rt,login_user.getId(),id,doc.getVid()) == false)
		{
			System.out.println("getDoc() you have no access right on doc:" + id);
			writeJson(rt, response);	
			return;
		}
		
		String content = doc.getContent();
        if( null !=content){
        	content = content.replaceAll("\t","");
        }
		doc.setContent(JSONObject.toJSONString(content));
		
		//System.out.println(rt.getData());
		rt.setData(doc);
		writeJson(rt, response);
	}

	/****************   lock a Doc ******************/
	@RequestMapping("/lockDoc.do")  //lock Doc主要用于用户锁定doc
	public void lockDoc(Integer docId,Integer reposId, Integer lockType, HttpSession session,HttpServletRequest request,HttpServletResponse response){
		System.out.println("lockDoc docId: " + docId + " reposId: " + reposId + " lockType: " + lockType);
		
		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
		}
		
		//检查用户是否有权限新增文件
		if(checkUserEditRight(rt,login_user.getId(),docId,reposId) == false)
		{
			writeJson(rt, response);	
			return;
		}
		
		Doc doc = null;
		synchronized(syncLock)
		{
			//Try to lock the Doc
			doc = lockDoc(docId,lockType,login_user,rt);
			if(doc == null)
			{
				unlock(); //线程锁
				System.out.println("Failed to lock Doc: " + docId);
				writeJson(rt, response);
				return;			
			}
			unlock(); //线程锁
		}
		
		System.out.println("lockDoc docId: " + docId + " success");
		rt.setData(doc);
		writeJson(rt, response);	
	}
	
	/****************   get Document History (logList) ******************/
	@RequestMapping("/getDocHistory.do")
	public void getDocHistory(Integer reposId,String docPath,HttpServletRequest request,HttpServletResponse response){
		System.out.println("getDocHistory docPath: " + docPath + " reposId:" + reposId);
		
		ReturnAjax rt = new ReturnAjax();
		
		if(reposId == null)
		{
			rt.setError("reposId is null");
			writeJson(rt, response);
			return;
		}
		
		Repos repos = reposService.getRepos(reposId);
		if(repos == null)
		{
			rt.setError("仓库 " + reposId + " 不存在！");
			writeJson(rt, response);
			return;
		}
		
		List<LogEntry> logList = svnGetHistory(repos,docPath);
		rt.setData(logList);
		writeJson(rt, response);
	}
	
	/* 文件搜索与排序  */
	@RequestMapping("/searchDoc.do")
	public void searchDoc(HttpServletResponse response,HttpSession session,String searchWord,String sort,Integer reposId,Integer pDocId){
		System.out.println("searchDoc searchWord: " + searchWord + " sort:" + sort);
		
		ReturnAjax rt = new ReturnAjax();
		User login_user = (User) session.getAttribute("login_user");
		if(login_user == null)
		{
			rt.setError("用户未登录，请先登录！");
			writeJson(rt, response);			
			return;
			
		}else{
			HashMap<String, Object> params = new HashMap<String, Object>();
			params.put("reposId", reposId);	//reposId为空则search所有仓库下的文件
			params.put("pDocId", pDocId);	//pDocId为空则search仓库下所有文件
					
			if(sort!=null&&sort.length()>0)
			{
				List<Map<String, Object>> sortList = GsonUtils.getMapList(sort);
				params.put("sortList", sortList);
			}
			
			//使用Lucene进行全文搜索，结果存入param以便后续进行数据库查询
			if(searchWord!=null&&!"".equals(searchWord)){
				try {
					params.put("name", searchWord);
					List<String> idList = LuceneUtil2.fuzzySearch(searchWord, "doc");
		        	for(int i=0; i < idList.size(); i++)
		        	{
		        		System.out.println(idList.get(i));
		        	}
		        	
					List<String> ids = new ArrayList<String>();
					for(String s:idList){
						String[] tmp = s.split(":");
						ids.add(tmp[0]);
					}
					params.put("ids", ids.toString().replace("[", "").replace("]", ""));
					System.out.println(idList.toString());
				} catch (Exception e) {
					System.out.println("LuceneUtil2.search 异常");
					e.printStackTrace();
				}
			}else{
				params.put("name", "");
			}
			
			//根据params参数查询docList
			List<Doc> list = reposService.queryDocList(params);
			rt.setData(list);
		}
		writeJson(rt, response);
	}

	/********************************** Functions For Application Layer
	 * @param content 
	 * @param commitUser2 
	 * @param chunkSize 
	 * @param chunkNum ****************************************/
	//底层addDoc接口
	private Integer addDoc(String name, String content, Integer type, MultipartFile uploadFile, Integer fileSize, String checkSum,Integer reposId,Integer parentId, 
			Integer chunkNum, Integer chunkSize, String chunkParentPath, String commitMsg,String commitUser,User login_user, ReturnAjax rt) {
		Repos repos = reposService.getRepos(reposId);
		//get parentPath
		String parentPath = getParentPath(parentId);
		String reposRPath = getReposRealPath(repos);
		String localDocRPath = reposRPath + parentPath + name;
		
		//判断目录下是否有同名节点 
		Doc tempDoc = getDocByName(name,parentId,reposId);
		if(tempDoc != null)
		{
			if(type == 2)	//如果是则目录直接成功
			{
				rt.setMsg("Node: " + name +" 已存在！", "dirExists");
				rt.setData(tempDoc);
			}
			else
			{
				rt.setError("Node: " + name +" 已存在！");
				System.out.println("addDoc() " + name + " 已存在");
			}
			return null;		
		}
		
		//以下代码不可重入，使用syncLock进行同步
		Doc doc = new Doc();
		synchronized(syncLock)
		{
			//Check if parentDoc was absolutely locked (LockState == 2)
			if(isParentDocLocked(parentId,null,rt))
			{	
				unlock(); //线程锁
				rt.setError("ParentNode: " + parentId +" is locked！");	
				System.out.println("ParentNode: " + parentId +" is locked！");
				return null;			
			}
				
			//新建doc记录,并锁定
			doc.setName(name);
			doc.setType(type);
			doc.setSize(fileSize);
			doc.setCheckSum(checkSum);
			doc.setContent(content);
			doc.setPath(parentPath);
			doc.setVid(reposId);
			doc.setPid(parentId);
			doc.setCreator(login_user.getId());
			//set createTime
			long nowTimeStamp = new Date().getTime();//获取当前系统时间戳
			doc.setCreateTime(nowTimeStamp);
			doc.setLatestEditTime(nowTimeStamp);
			doc.setLatestEditor(login_user.getId());
			doc.setState(2);	//doc的状态为不可用
			doc.setLockBy(login_user.getId());	//LockBy login_user, it was used with state
			long lockTime = nowTimeStamp + 24*60*60*1000;
			doc.setLockTime(lockTime);	//Set lockTime
			if(reposService.addDoc(doc) == 0)
			{			
				unlock();
				rt.setError("Add Node: " + name +" Failed！");
				System.out.println("addDoc() addDoc to db failed");
				return null;
			}
			unlock();
		}
		
		System.out.println("id: " + doc.getId());
		
		if(uploadFile == null)
		{
			if(createRealDoc(reposRPath,parentPath,name,type, rt) == false)
			{		
				String MsgInfo = "createRealDoc " + name +" Failed";
				rt.setError(MsgInfo);
				System.out.println("createRealDoc Failed");
				//删除新建的doc,我需要假设总是会成功,如果失败了也只是在Log中提示失败
				if(reposService.deleteDoc(doc.getId()) == 0)	
				{
					MsgInfo += " and delete Node Failed";
					System.out.println("Delete Node: " + doc.getId() +" failed!");
					rt.setError(MsgInfo);
				}
				return null;
			}
		}
		else
		{
			if(updateRealDoc(reposRPath,parentPath,name,doc.getType(),fileSize,checkSum,uploadFile,chunkNum,chunkSize,chunkParentPath,rt) == false)
			{		
				String MsgInfo = "updateRealDoc " + name +" Failed";
				rt.setError(MsgInfo);
				System.out.println("updateRealDoc Failed");
				//删除新建的doc,我需要假设总是会成功,如果失败了也只是在Log中提示失败
				if(reposService.deleteDoc(doc.getId()) == 0)	
				{
					MsgInfo += " and delete Node Failed";
					System.out.println("Delete Node: " + doc.getId() +" failed!");
					rt.setError(MsgInfo);
				}
				return null;
			}
		}
		//commit to history db
		if(svnRealDocAdd(repos,parentPath,name,type,commitMsg,commitUser,rt) == false)
		{
			System.out.println("svnRealDocAdd Failed");
			String MsgInfo = "svnRealDocAdd Failed";
			//我们总是假设rollback总是会成功，失败了也是返回错误信息，方便分析
			if(deleteFile(localDocRPath) == false)
			{						
				MsgInfo += " and deleteFile Failed";
			}
			if(reposService.deleteDoc(doc.getId()) == 0)
			{
				MsgInfo += " and delete Node Failed";						
			}
			rt.setError(MsgInfo);
			//writeJson(rt, response);	
			return null;
		}
		
		//只有在content非空的时候才创建VDOC
		if(null != content && !"".equals(content))
		{
			String reposVPath = getReposVirtualPath(repos);
			String docVName = getDocVPath(doc);
			if(createVirtualDoc(reposVPath,docVName,content,rt) == true)
			{
				if(svnVirtualDocAdd(repos, docVName, commitMsg, commitUser,rt) ==false)
				{
					System.out.println("addDoc() svnVirtualDocAdd Failed " + docVName);
					rt.setMsgInfo("svnVirtualDocAdd Failed");			
				}
			}
			else
			{
				System.out.println("addDoc() createVirtualDoc Failed " + reposVPath + docVName);
				rt.setMsgInfo("createVirtualDoc Failed");
			}
		}
		
		//启用doc
		if(unlockDoc(doc.getId(),login_user,null) == false)
		{
			rt.setError("unlockDoc Failed");
			//writeJson(rt, response);	
			return null;
		}
		rt.setMsg("新增成功", "isNewNode");
		rt.setData(doc);
		return doc.getId();
	}
	
	//释放线程锁
	private void unlock() {
		unlockSyncLock(syncLock);
	}	
	private void unlockSyncLock(Object syncLock) {
		syncLock.notifyAll();//唤醒等待线程
		//下面这段代码是因为参考了网上的一个Demo说wait是释放锁，我勒了个区去，留着作纪念
		//try {
		//	syncLock.wait();	//线程睡眠，等待syncLock.notify/notifyAll唤醒
		//} catch (InterruptedException e) {
		//	e.printStackTrace();
		//}
	}  

	//底层deleteDoc接口
	private boolean deleteDoc(Integer docId, Integer reposId,Integer parentId, 
				String commitMsg,String commitUser,User login_user, ReturnAjax rt) {

		Doc doc = null;
		synchronized(syncLock)
		{

			//是否需要检查subDoc is locked? 不需要！ 因为如果doc有subDoc那么deleteRealDoc会抱错
		
			//Try to lock the Doc
			doc = lockDoc(docId,2,login_user,rt);
			if(doc == null)
			{
				unlock(); //线程锁
				System.out.println("Failed to lock Doc: " + docId);
				return false;			
			}
			unlock(); //线程锁
		}
		
		Repos repos = reposService.getRepos(reposId);
		//get parentPath
		String parentPath = getParentPath(parentId);		
		//get RealDoc Full ParentPath
		String reposRPath = getReposRealPath(repos);
		
		//删除实体文件
		String name = doc.getName();
		
		if(false == deleteSubDocs(docId,reposId,commitMsg,commitUser,login_user,rt))
		{
			String MsgInfo = "deleteSubDocs of doc " + docId +" Failed";
			if(unlockDoc(docId,login_user,doc) == false)
			{
				MsgInfo += " and unlockDoc Failed";						
			}
			rt.setMsgInfo(MsgInfo);
			return false;		
		}
		
		if(deleteRealDoc(reposRPath, parentPath, name,doc.getType(),rt) == false)
		{
			String MsgInfo = "deleteRealDoc Failed";
			rt.setError(parentPath + name + "删除失败！");
			if(unlockDoc(docId,login_user,doc) == false)
			{
				MsgInfo += " and unlockDoc Failed";						
			}
			rt.setError(MsgInfo);
			return false;
		}
			
		//需要将文件Commit到SVN上去
		if(svnRealDocDelete(repos,parentPath,name,doc.getType(),commitMsg,commitUser,rt) == false)
		{
			System.out.println("svnRealDocDelete Failed");
			String MsgInfo = "svnRealDocDelete Failed";
			//我们总是假设rollback总是会成功，失败了也是返回错误信息，方便分析
			if(svnRevertRealDoc(repos,parentPath,name,doc.getType(),rt) == false)
			{						
				MsgInfo += " and revertFile Failed";
			}
			if(unlockDoc(docId,login_user,doc) == false)
			{
				MsgInfo += " and unlockDoc Failed";						
			}
			rt.setError(MsgInfo);
			return false;
		}				
		
		//删除虚拟文件
		String reposVPath = getReposVirtualPath(repos);
		String docVName = getDocVPath(doc);
		String localDocVPath = reposVPath + docVName;
		if(deleteVirtualDoc(reposVPath,docVName,rt) == false)
		{
			System.out.println("deleteDoc() delDir Failed " + localDocVPath);
			rt.setMsgInfo("Delete Virtual Doc Failed:" + localDocVPath);
		}
		else
		{
			if(svnVirtualDocDelete(repos,docVName,commitMsg,commitUser,rt) == false)
			{
				System.out.println("deleteDoc() delDir Failed " + localDocVPath);
				rt.setMsgInfo("Delete Virtual Doc Failed:" + localDocVPath);
				svnRevertVirtualDoc(repos,docVName);
			}
		}
		
		//保存删除前的CheckSum，用于预览文件的删除
		String CheckSum = doc.getCheckSum();
				
		//删除Node
		if(reposService.deleteDoc(docId) == 0)
		{	
			rt.setError("不可恢复系统错误：deleteDoc Failed");
			return false;
		}
		rt.setData(doc);
		
		//Delete tmp files for this doc (preview)
		deletePreviewFile(CheckSum);

		return true;
	}

	//删除预览文件
	private void deletePreviewFile(String checkSum) {
		String dstName = checkSum + ".pdf";
		String dstPath = getWebTmpPath() + "preview/" + dstName;
		delFileOrDir(dstPath);
	}

	private boolean deleteSubDocs(Integer docId, Integer reposId,
			String commitMsg, String commitUser, User login_user, ReturnAjax rt) {
		
		Doc doc = new Doc();
		doc.setPid(docId);
		List<Doc> subDocList = reposService.getDocList(doc);
		for(int i=0; i< subDocList.size(); i++)
		{
			Doc subDoc = subDocList.get(i);
			if(false == deleteDoc(subDoc.getId(),reposId,docId,commitMsg,commitUser,login_user,rt))
			{
				return false;
			}
		}
		return true;
	}

	//底层updateDoc接口
	private void updateDoc(Integer docId, MultipartFile uploadFile,Integer fileSize,String checkSum,Integer reposId,Integer parentId, 
			Integer chunkNum, Integer chunkSize, String chunkParentPath, String commitMsg,String commitUser,User login_user, ReturnAjax rt) {

		Doc doc = null;
		synchronized(syncLock)
		{
			//Try to lock the doc
			doc = lockDoc(docId, 1, login_user, rt);
			if(doc == null)
			{
				unlock(); //线程锁
	
				System.out.println("updateDoc() lockDoc " + docId +" Failed！");
				return;
			}
			unlock(); //线程锁
			
		}
		
		//Save oldCheckSum
		String oldCheckSum = doc.getCheckSum();
		
		//为了避免执行到SVNcommit成功但数据库操作失败，所以先将checkSum更新掉
		doc.setCheckSum(checkSum);
		if(reposService.updateDoc(doc) == 0)
		{
			rt.setError("系统异常：操作数据库失败");
			rt.setMsgData("updateDoc() update Doc CheckSum Failed");
			return;
		}
		
		Repos repos = reposService.getRepos(reposId);
		//get RealDoc Full ParentPath
		String reposRPath =  getReposRealPath(repos);
		//get parentPath
		String parentPath = getParentPath(parentId);		
		//Get the file name
		String name = doc.getName();
		System.out.println("updateDoc() name:" + name);

		//替换文件
		if(isRealFS(repos.getType())) //0：虚拟文件系统   1： 普通文件系统	
		{
			//保存文件信息
			if(updateRealDoc(reposRPath,parentPath,name,doc.getType(),fileSize,checkSum,uploadFile,chunkNum,chunkSize,chunkParentPath,rt) == false)
			{
				if(unlockDoc(docId,login_user,doc) == false)
				{
					System.out.println("updateDoc() saveFile " + docId +" Failed and unlockDoc Failed");
					rt.setError("Failed to updateRealDoc " + name + " and unlock Doc");
				}
				else
				{	
					System.out.println("updateDoc() saveFile " + docId +" Failed, unlockDoc Ok");
					rt.setError("Failed to updateRealDoc " + name + ", unlockDoc Ok");
				}
				return;
			}
			
			//需要将文件Commit到SVN上去
			if(svnRealDocCommit(repos,parentPath,name,doc.getType(),commitMsg,commitUser,rt) == false)
			{
				System.out.println("updateDoc() svnRealDocCommit Failed:" + parentPath + name);
				String MsgInfo = "svnRealDocCommit Failed";
				//我们总是假设rollback总是会成功，失败了也是返回错误信息，方便分析
				if(svnRevertRealDoc(repos,parentPath,name,doc.getType(),rt) == false)
				{						
					MsgInfo += " and revertFile Failed";
				}
				//还原doc记录的状态
				if(unlockDoc(docId,login_user,doc) == false)
				{
					MsgInfo += " and unlockDoc Failed";						
				}
				rt.setError(MsgInfo);	
				return;
			}
		}
		
		
		//updateDoc Info and unlock
		doc.setSize(fileSize);
		//doc.setState(preLockState);	//Recover the lockState of Doc
		//doc.setLockBy(preLockBy);	//
		//doc.setLockTime(preLockTime);	//Set lockTime
		doc.setCheckSum(checkSum);
		//set lastEditTime
		long nowTimeStamp = new Date().getTime();//获取当前系统时间戳
		doc.setLatestEditTime(nowTimeStamp);
		doc.setLatestEditor(login_user.getId());
		if(reposService.updateDoc(doc) == 0)
		{
			rt.setError("不可恢复系统错误：updateAndunlockDoc Failed");
			return;
		}
		
		//Delete PreviewFile
		deletePreviewFile(oldCheckSum);
	}

	//底层renameDoc接口
	private void renameDoc(Integer docId, String newname,Integer reposId,Integer parentId, 
			String commitMsg,String commitUser,User login_user, ReturnAjax rt) {
		
		Doc doc = null;
		synchronized(syncLock)
		{
			//Renmae Dir 需要检查其子目录是否上锁
			if(isSubDocLocked(docId,rt) == true)
			{
				unlock(); //线程锁
	
				System.out.println("renameDoc() subDoc of " + docId +" was locked！");
				return;
			}
			
			//Try to lockDoc
			doc = lockDoc(docId,2,login_user,rt);
			if(doc == null)
			{
				unlock(); //线程锁
				
				System.out.println("renameDoc() lockDoc " + docId +" Failed！");
				return;
			}
			unlock(); //线程锁
		}
		
		Repos repos = reposService.getRepos(reposId);
		String reposRPath = getReposRealPath(repos);
		String parentPath = getParentPath(parentId);
		String oldname = doc.getName();
		
		//修改实文件名字	
		if(moveRealDoc(reposRPath,parentPath,oldname,parentPath,newname,doc.getType(),rt) == false)
		{
			if(unlockDoc(docId,login_user,doc) == false)
			{
				rt.setError(oldname + " renameRealDoc失败！ and unlockDoc " + docId +" Failed！");
				return;
			}
			else
			{
				rt.setError(oldname + " renameRealDoc失败！");
				return;
			}
		}
		else
		{
			//commit to history db
			if(svnRealDocMove(repos,parentPath,oldname,parentPath,newname,doc.getType(),commitMsg,commitUser,rt) == false)
			{
				//我们假定版本提交总是会成功，因此报错不处理
				System.out.println("renameDoc() svnRealDocMove Failed");
				String MsgInfo = "svnRealDocMove Failed";
				
				if(moveRealDoc(reposRPath,parentPath,newname,parentPath,oldname,doc.getType(),rt) == false)
				{
					MsgInfo += " and moveRealDoc Back Failed";
				}
				if(unlockDoc(docId,login_user,doc) == false)
				{
					MsgInfo += " and unlockDoc Failed";						
				}
				rt.setError(MsgInfo);
				return;
			}	
		}
		
		//更新doc name
		Doc tempDoc = new Doc();
		tempDoc.setId(docId);
		tempDoc.setName(newname);
		//set lastEditTime
		long nowTimeStamp = new Date().getTime();//获取当前系统时间戳
		tempDoc.setLatestEditTime(nowTimeStamp);
		tempDoc.setLatestEditor(login_user.getId());
		if(reposService.updateDoc(tempDoc) == 0)
		{
			rt.setError("不可恢复系统错误：Failed to update doc name");
			return;
		}
		
		//unlock doc
		if(unlockDoc(docId,login_user,doc) == false)
		{
			rt.setError("unlockDoc failed");	
		}
		return;
	}
	
	//底层moveDoc接口
	private void moveDoc(Integer docId, Integer reposId,Integer parentId,Integer dstPid,  
			String commitMsg,String commitUser,User login_user, ReturnAjax rt) {

		Doc doc = null;
		Doc dstPDoc = null;
		synchronized(syncLock)
		{
			//Try to lock Doc
			if(isSubDocLocked(docId,rt) == true)
			{
				unlock(); //线程锁
	
				System.out.println("subDoc of " + docId +" Locked！");
				return;
			}
			
			doc = lockDoc(docId,2,login_user,rt);
			if(doc == null)
			{
				unlock(); //线程锁
	
				System.out.println("lockDoc " + docId +" Failed！");
				return;
			}
			
			//Try to lock dstPid
			if(dstPid !=0)
			{
				dstPDoc = lockDoc(dstPid,2,login_user,rt);
				if(dstPDoc== null)
				{
					unlock(); //线程锁
	
					System.out.println("moveDoc() fail to lock dstPid" + dstPid);
					unlockDoc(docId,login_user,doc);	//Try to unlock the doc
					return;
				}
			}
			unlock(); //线程锁
		}
		
		//移动当前节点
		Integer orgPid = doc.getPid();
		System.out.println("moveDoc id:" + docId + " orgPid: " + orgPid + " dstPid: " + dstPid);
		
		String srcParentPath = getParentPath(orgPid);		
		String dstParentPath = getParentPath(dstPid);
		
		Repos repos = reposService.getRepos(reposId);
		String reposRPath = getReposRealPath(repos);
		
		String filename = doc.getName();
		String srcDocRPath = srcParentPath + filename;
		String dstDocRPath = dstParentPath + filename;
		System.out.println("srcDocRPath: " + srcDocRPath + " dstDocRPath: " + dstDocRPath);
		
		//只有当orgPid != dstPid 不同时才进行文件移动，否则文件已在正确位置，只需要更新Doc记录
		if(!orgPid.equals(dstPid))
		{
			System.out.println("moveDoc() docId:" + docId + " orgPid: " + orgPid + " dstPid: " + dstPid);
			if(moveRealDoc(reposRPath,srcParentPath,filename,dstParentPath,filename,doc.getType(),rt) == false)
			{
				String MsgInfo = "文件移动失败！";
				System.out.println("moveDoc() 文件: " + filename + " 移动失败");
				if(unlockDoc(docId,login_user,doc) == false)
				{
					MsgInfo += " and unlockDoc " + docId+ " failed ";
				}
				if(dstPid !=0 && unlockDoc(dstPid,login_user,dstPDoc) == false)
				{
					MsgInfo += " and unlockDoc " + dstPid+ " failed ";
				}
				rt.setError(MsgInfo);
				return;
			}
			
			//需要将文件Commit到SVN上去：先执行svn的移动
			if(svnRealDocMove(repos, srcParentPath,filename, dstParentPath, filename,doc.getType(),commitMsg, commitUser,rt) == false)
			{
				System.out.println("moveDoc() svnRealDocMove Failed");
				String MsgInfo = "svnRealDocMove Failed";
				if(moveRealDoc(reposRPath,dstParentPath,filename,srcParentPath,filename,doc.getType(),rt) == false)
				{
					MsgInfo += "and changeDirectory Failed";
				}
				
				if(unlockDoc(docId,login_user,doc) == false)
				{
					MsgInfo += " and unlockDoc " + docId+ " failed ";
				}
				if(dstPid !=0 && unlockDoc(dstPid,login_user,dstPDoc) == false)
				{
					MsgInfo += " and unlockDoc " + dstPid+ " failed ";
				}
				rt.setError(MsgInfo);
				return;					
			}
		}
		
		//更新doc pid and path
		Doc tempDoc = new Doc();
		tempDoc.setId(docId);
		tempDoc.setPath(dstParentPath);
		tempDoc.setPid(dstPid);
		//set lastEditTime
		long nowTimeStamp = new Date().getTime();//获取当前系统时间戳
		tempDoc.setLatestEditTime(nowTimeStamp);
		tempDoc.setLatestEditor(login_user.getId());
		if(reposService.updateDoc(tempDoc) == 0)
		{
			rt.setError("不可恢复系统错误：Failed to update doc pid and path");
			return;				
		}
		
		//Unlock Docs
		String MsgInfo = null; 
		if(unlockDoc(docId,login_user,doc) == false)
		{
			MsgInfo = "unlockDoc " + docId+ " failed ";
		}
		if(dstPid !=0 && unlockDoc(dstPid,login_user,dstPDoc) == false)
		{
			MsgInfo += " and unlockDoc " + dstPid+ " failed ";
		}
		if(MsgInfo!=null)
		{
			rt.setError(MsgInfo);
		}
		return;
	}
	
	//底层copyDoc接口
	private void copyDoc(Integer docId,String srcName,String dstName, Integer type, Integer reposId,Integer parentId, Integer dstPid,
			String commitMsg,String commitUser,User login_user, ReturnAjax rt) {
		
		Repos repos = reposService.getRepos(reposId);
		String reposRPath =  getReposRealPath(repos);

		//get parentPath
		String parentPath = getParentPath(parentId);		
		//目标路径
		String dstParentPath = getParentPath(dstPid);		
		
		//远程仓库相对路径
		//String srcDocRPath = parentPath  + name;
		//String dstDocRPath = dstParentPath + name;
		//String srcDocFullRPath = reposRPath + parentPath + name;
		//String dstDocFullRPath = reposRPath + dstParentPath + name;
		
		//判断节点是否已存在
		if(isNodeExist(dstName,dstPid,reposId) == true)
		{
			rt.setError("Node: " + dstName +" 已存在！");
			return;
		}

		Doc doc = null;
		synchronized(syncLock)
		{
			//Try to lock the srcDoc
			doc = lockDoc(docId,1,login_user,rt);
			if(doc == null)
			{
				unlock(); //线程锁
	
				System.out.println("copyDoc lock " + docId + " Failed");
				return;
			}
			
			//新建doc记录
			doc.setId(null);	//置空id,以便新建一个doc
			doc.setName(dstName);
			//doc.setType(type);
			//doc.setContent("#" + name);
			doc.setPath(dstParentPath);
			//doc.setVid(reposId);
			doc.setPid(dstPid);
			doc.setCreator(login_user.getId());
			//set createTime
			long nowTimeStamp = new Date().getTime(); //当前时间的时间戳
			doc.setCreateTime(nowTimeStamp);
			//set lastEditTime
			doc.setLatestEditTime(nowTimeStamp);
			doc.setLatestEditor(login_user.getId());
			doc.setState(2);	//doc的状态为不可用
			doc.setLockBy(login_user.getId());	//set LockBy
			long lockTime = nowTimeStamp + 24*60*60*1000;
			doc.setLockTime(lockTime);	//Set lockTime
			if(reposService.addDoc(doc) == 0)
			{
				unlock(); //线程锁
	
				rt.setError("Add Node: " + dstName +" Failed！");
				unlockDoc(docId,login_user,null);
				return;
			}
			unlock(); //线程锁
		}
		
		System.out.println("id: " + doc.getId());
		
		//复制文件或目录，注意这个接口只会复制单个文件
		if(copyRealDoc(reposRPath,parentPath,srcName,dstParentPath,dstName,doc.getType(),rt) == false)
		{
			System.out.println("copy " + srcName + " to " + dstName + " 失败");
			String MsgInfo = "copyRealDoc from " + srcName + " to " + dstName + "Failed";
			//删除新建的doc,我需要假设总是会成功,如果失败了也只是在Log中提示失败
			if(reposService.deleteDoc(doc.getId()) == 0)	
			{
				System.out.println("Delete Node: " + doc.getId() +" failed!");
				MsgInfo += " and delete Node Failed";
			}
			if(unlockDoc(docId,login_user,null) == false)
			{
				MsgInfo += " and unlock " + docId +" Failed";	
			}
			rt.setError(MsgInfo);
			return;
		}
			
		//需要将文件Commit到SVN上去
		boolean ret = false;
		String MsgInfo = "";
		if(type == 1) 
		{
			ret = svnRealDocCopy(repos,parentPath,srcName,dstParentPath,dstName,type,commitMsg, commitUser,rt);
			MsgInfo = "svnRealDocCopy Failed";
		}
		else //目录则在版本仓库新建，因为复制操作每次只复制一个节点，直接调用copy会导致目录下的所有节点都被复制
		{
			ret = svnRealDocAdd(repos,dstParentPath,dstName,type,commitMsg,commitUser,rt);
			MsgInfo = "svnRealDocAdd Failed";
		}
			
			
		if(ret == false)
		{
			System.out.println("copyDoc() " + MsgInfo);
			//我们总是假设rollback总是会成功，失败了也是返回错误信息，方便分析
			if(deleteRealDoc(reposRPath,parentPath,dstName,type,rt) == false)
			{						
				MsgInfo += " and deleteFile Failed";
			}
			if(reposService.deleteDoc(doc.getId()) == 0)
			{
				MsgInfo += " and delete Node Failed";						
			}
			if(unlockDoc(docId,login_user,null) == false)
			{
				MsgInfo += " and unlock " + docId +" Failed";	
			}
			rt.setError(MsgInfo);
			return;
		}				
		
		//content非空时才去创建虚拟文件目录
		if(null != doc.getContent() && !"".equals(doc.getContent()))
		{
			String reposVPath = getReposVirtualPath(repos);
			Doc srcDoc = reposService.getDoc(docId);
			String srcDocVName = getDocVPath(srcDoc);
			String dstDocVName = getDocVPath(doc);
			if(copyVirtualDoc(reposVPath,srcDocVName,dstDocVName,rt) == true)
			{
				if(svnVirtualDocCopy(repos,srcDocVName,dstDocVName, commitMsg, commitUser,rt) == false)
				{
					System.out.println("copyDoc() svnVirtualDocCopy " + srcDocVName + " to " + dstDocVName + " Failed");							
				}
			}
			else
			{
				System.out.println("copyDoc() copyVirtualDoc " + srcDocVName + " to " + dstDocVName + " Failed");						
			}
		}
		
		//启用doc
		MsgInfo = null;
		if(unlockDoc(doc.getId(),login_user,null) == false)
		{	
			MsgInfo ="unlockDoc " +doc.getId() + " Failed";;
		}
		//Unlock srcDoc 
		if(unlockDoc(docId,login_user,null) == false)
		{
			MsgInfo += " and unlock " + docId +" Failed";	
		}
		if(MsgInfo != null)
		{
			rt.setError(MsgInfo);
		}
		
		//只返回最上层的doc记录
		if(rt.getData() == null)
		{
			rt.setData(doc);
		}
		
		//copySubDocs
		copySubDocs(docId, reposId, doc.getId(),commitMsg,commitUser,login_user,rt); 
	}

	private void copySubDocs(Integer docId, Integer reposId, Integer dstParentId,
			String commitMsg, String commitUser, User login_user, ReturnAjax rt) {
		Doc doc = new Doc();
		doc.setPid(docId);
		List<Doc> subDocList = reposService.getDocList(doc);
		for(int i=0; i< subDocList.size(); i++)
		{
			Doc subDoc = subDocList.get(i);
			String subDocName = subDoc.getName();
			copyDoc(subDoc.getId(),subDocName,subDocName, subDoc.getType(), reposId, docId, dstParentId,commitMsg,commitUser,login_user,rt);
		}
	}

	private void updateDocContent(Integer id,String content, String commitMsg, String commitUser, User login_user,ReturnAjax rt) {
		Doc doc = null;
		synchronized(syncLock)
		{
			//Try to lock Doc
			doc = lockDoc(id,1,login_user,rt);
			if(doc== null)
			{
				unlock(); //线程锁
	
				System.out.println("updateDocContent() lockDoc Failed");
				return;
			}
			unlock(); //线程锁
		}
		
		Repos repos = reposService.getRepos(doc.getVid());
		
		//只更新内容部分
		Doc newDoc = new Doc();
		newDoc.setId(id);
		newDoc.setContent(content);
		//System.out.println("before: " + content);
		if(reposService.updateDoc(newDoc) == 0)
		{
			rt.setError("更新文件失败");
			return;			
		}	
		
		//Save the content to virtual file
		String reposVPath = getReposVirtualPath(repos);
		String docVName = getDocVPath(doc);
		String localVDocPath = reposVPath + docVName;
		
		System.out.println("updateDocContent() localVDocPath: " + localVDocPath);
		if(isFileExist(localVDocPath) == true)
		{
			if(saveVirtualDocContent(reposVPath,docVName, content,rt) == true)
			{
				if(repos.getVerCtrl() == 1)
				{
					svnVirtualDocCommit(repos, docVName, commitMsg, commitUser,rt);
				}
			}
		}
		else
		{	
			//创建虚拟文件目录：用户编辑保存时再考虑创建
			if(createVirtualDoc(reposVPath,docVName,content,rt) == true)
			{
				if(repos.getVerCtrl() == 1)
				{
					svnVirtualDocCommit(repos, docVName, commitMsg, commitUser,rt);
				}
			}
		}
		
		
		if(unlockDoc(id,login_user,doc) == false)
		{
			rt.setError("unlockDoc failed");	
		}		
	}
	
	/*********************Functions For DocLock *******************************/
	//Lock Doc
	private Doc lockDoc(Integer docId,Integer lockType, User login_user, ReturnAjax rt) {
		System.out.println("lockDoc() docId:" + docId + " lockType:" + lockType + " by " + login_user.getName());
		//确定文件节点是否可用
		Doc doc = reposService.getDoc(docId);
		if(doc == null)
		{
			rt.setError("Doc " + docId +" 不存在！");
			System.out.println("lockDoc() Doc: " + docId +" 不存在！");
			return null;
		}
		
		//check if the doc was locked (State!=0 && lockTime - curTime > 1 day)
		if(isDocLocked(doc,login_user,rt))
		{
			System.out.println("lockDoc() Doc " + docId +" was locked");
			return null;
		}
		
		//检查其父节点是否进行了递归锁定
		if(isParentDocLocked(doc.getPid(),login_user,rt))	//2: 全目录锁定
		{
			System.out.println("lockDoc() Parent Doc of " + docId +" was locked！");				
			return null;
		}
		
		//lockTime is the time to release lock 
		Doc lockDoc= new Doc();
		lockDoc.setId(docId);
		lockDoc.setState(lockType);	//doc的状态为不可用
		lockDoc.setLockBy(login_user.getId());
		long lockTime = new Date().getTime() + 24*60*60*1000;
		lockDoc.setLockTime(lockTime);	//Set lockTime
		if(reposService.updateDoc(lockDoc) == 0)
		{
			rt.setError("lock Doc:" + docId +"[" + doc.getName() +"]  failed");
			return null;
		}
		System.out.println("lockDoc() success docId:" + docId + " lockType:" + lockType + " by " + login_user.getName());
		return doc;
	}
	
	//确定当前doc是否被锁定
	private boolean isDocLocked(Doc doc,User login_user,ReturnAjax rt) {
		int lockState = doc.getState();	//0: not locked 1: lock doc only 2: lock doc and subDocs 3: lock doc for online edit
		if(lockState != 0)
		{
			if(doc.getLockBy() == login_user.getId())	//locked by login_user
			{
				System.out.println("Doc: " + doc.getId() +" was locked by user:" + doc.getLockBy() +" login_user:" + login_user.getId());
				return false;
			}
				
			if(isLockOutOfDate(doc) == false)
			{	
				User lockBy = userService.getUser(doc.getLockBy());
				rt.setError(doc.getName() +" was locked by " + lockBy.getName());
				System.out.println("Doc " + doc.getId()+ "[" + doc.getName() +"] was locked by " + doc.getLockBy() + " lockState:"+ doc.getState());;
				return true;						
			}
			else 
			{
				System.out.println("doc " + doc.getId()+ " " + doc.getName()  +" lock was out of date！");
				return false;
			}
		}
		return false;
	}

	private boolean isLockOutOfDate(Doc doc) {
		//check if the lock was out of date
		long curTime = new Date().getTime();
		long lockTime = doc.getLockTime();
		System.out.println("isLockOutOfDate() curTime:"+curTime+" lockTime:"+lockTime);
		if(curTime < lockTime)	//
		{
			System.out.println("isLockOutOfDate() Doc " + doc.getId()+ " " + doc.getName() +" was locked:" + doc.getState());
			return false;
		}

		//Lock 自动失效设计
		System.out.println("Doc: " +  doc.getId() +" lock is out of date！");
		return true;
	}

	//确定parentDoc是否被全部锁定
	private boolean isParentDocLocked(Integer parentDocId, User login_user,ReturnAjax rt) {
		if(parentDocId == 0)
		{
			return false;	//已经到了最上层
		}
		
		Doc doc = reposService.getDoc(parentDocId);
		if(doc == null)
		{
			System.out.println("isParentDocLocked() doc is null for parentDocId=" + parentDocId);
			return false;
		}
		
		Integer lockState = doc.getState();
		
		if(lockState == 2)	//1:lock doc only 2: lock doc and subDocs
		{
			if(login_user != null)
			{
				if(login_user.getId() == doc.getLockBy())
				{
					return false;
				}
			}
			
			long curTime = new Date().getTime();
			long lockTime = doc.getLockTime();	//time for lock release
			System.out.println("isParentDocLocked() curTime:"+curTime+" lockTime:"+lockTime);
			if(curTime < lockTime)
			{
				rt.setError("parentDoc " + parentDocId + "[" + doc.getName() + "] was locked:" + lockState);
				System.out.println("getParentLockState() " + parentDocId + " is locked!");
				return true;
			}
		}
		return isParentDocLocked(doc.getPid(),login_user,rt);
	}
	
	//docId目录下是否有锁定的doc(包括所有锁定状态)
	//Check if any subDoc under docId was locked, you need to check it when you want to rename/move/copy the Directory
	private boolean isSubDocLocked(Integer docId, ReturnAjax rt)
	{
		//Set the query condition to get the SubDocList of DocId
		Doc qDoc = new Doc();
		qDoc.setPid(docId);

		//get the subDocList 
		List<Doc> SubDocList = reposService.getDocList(qDoc);
		for(int i=0;i<SubDocList.size();i++)
		{
			Doc subDoc =SubDocList.get(i);
			if(subDoc.getState() != 0)
			{
				rt.setError("subDoc " + subDoc.getId() + "[" +  subDoc.getName() + "] is locked:" + subDoc.getState());
				System.out.println("isSubDocLocked() " + subDoc.getId() + " is locked!");
				return true;
			}
		}
		
		//If there is subDoc which is directory, we need to go into the subDoc to check the lockSatate of subSubDoc
		for(int i=0;i<SubDocList.size();i++)
		{
			Doc subDoc =SubDocList.get(i);
			if(subDoc.getType() == 2)
			{
				if(isSubDocLocked(subDoc.getId(),rt) == true)
				{
					return true;
				}
			}
		}
				
		return false;
	}
	

	//Unlock Doc
	private boolean unlockDoc(Integer docId, User login_user, Doc preLockInfo) {
		Doc curDoc = reposService.getDocInfo(docId);
		if(curDoc == null)
		{
			System.out.println("unlockDoc() doc is null " + docId);
			return false;
		}
		
		if(curDoc.getState() == 0)
		{
			System.out.println("unlockDoc() doc was not locked:" + curDoc.getState());			
			return true;
		}
		
		Integer lockBy = curDoc.getLockBy();
		if(lockBy != null && lockBy == login_user.getId())
		{
			Doc revertDoc = new Doc();
			revertDoc.setId(docId);	
			
			if(preLockInfo == null)	//Unlock
			{
				revertDoc.setState(0);	//
				revertDoc.setLockBy(0);	//
				revertDoc.setLockTime((long)0);	//Set lockTime
			}
			else	//Revert to preLockState
			{
				revertDoc.setState(preLockInfo.getState());	//
				revertDoc.setLockBy(preLockInfo.getLockBy());	//
				revertDoc.setLockTime(preLockInfo.getLockTime());	//Set lockTime
			}
			
			if(reposService.updateDoc(revertDoc) == 0)
			{
				System.out.println("unlockDoc() updateDoc Failed!");
				return false;
			}
		}
		else
		{
			System.out.println("unlockDoc() doc was not locked by " + login_user.getName());
			return false;
		}
		
		System.out.println("unlockDoc() success:" + docId);
		return true;
	}
	
	/*************************** Functions For Real and Virtual Doc Operation ***********************************/
	//create Real Doc
	private boolean createRealDoc(String reposRPath,String parentPath, String name, Integer type, ReturnAjax rt) {
		//获取 doc parentPath
		String localParentPath =  reposRPath + parentPath;
		String localDocPath = localParentPath + name;
		System.out.println("createRealDoc() localParentPath:" + localParentPath);
		
		if(type == 2) //目录
		{
			if(isFileExist(localDocPath) == true)
			{
				System.out.println("createRealDoc() 目录 " +localDocPath + "　已存在！");
				rt.setMsgData("createRealDoc() 目录 " +localDocPath + "　已存在！");
				return false;
			}
			
			if(false == createDir(localDocPath))
			{
				System.out.println("createRealDoc() 目录 " +localDocPath + " 创建失败！");
				rt.setMsgData("createRealDoc() 目录 " +localDocPath + " 创建失败！");
				return false;
			}				
		}
		else
		{
			if(isFileExist(localDocPath) == true)
			{
				System.out.println("createRealDoc() 文件 " +localDocPath + " 已存在！");
				rt.setMsgData("createRealDoc() 文件 " +localDocPath + " 已存在！");
				return false;
			}
			
			if(false == createFile(localParentPath,name))
			{
				System.out.println("createRealDoc() 文件 " + localDocPath + "创建失败！");
				rt.setMsgData("createRealDoc() createFile 文件 " + localDocPath + "创建失败！");
				return false;					
			}
		}
		return true;
	}
	
	private boolean deleteRealDoc(String reposRPath, String parentPath, String name, Integer type, ReturnAjax rt) {
		String localDocPath = reposRPath + parentPath + name;
		if(type == 2)
		{
			if(delDir(localDocPath) == false)
			{
				System.out.println("deleteRealDoc() delDir " + localDocPath + "删除失败！");
				rt.setMsgData("deleteRealDoc() delDir " + localDocPath + "删除失败！");
				return false;
			}
		}	
		else 
		{
			if(deleteFile(localDocPath) == false)
			{
				System.out.println("deleteRealDoc() deleteFile " + localDocPath + "删除失败！");
				rt.setMsgData("deleteRealDoc() deleteFile " + localDocPath + "删除失败！");
				return false;
			}
		}
		return true;
	}
	
	private boolean updateRealDoc(String reposRPath,String parentPath,String name,Integer type, Integer fileSize, String fileCheckSum,
			MultipartFile uploadFile, Integer chunkNum, Integer chunkSize, String chunkParentPath, ReturnAjax rt) {
		String localDocParentPath = reposRPath + parentPath;
		String retName = null;
		try {
			if(null == chunkNum)	//非分片上传
			{
				retName = saveFile(uploadFile, localDocParentPath,name);
			}
			else
			{
				retName = combineChunks(localDocParentPath,name,chunkNum,chunkSize,chunkParentPath);
			}
			//Verify the size and FileCheckSum
			if(false == checkFileSizeAndCheckSum(localDocParentPath,name,fileSize,fileCheckSum))
			{
				System.out.println("updateRealDoc() checkFileSizeAndCheckSum Error");
				return false;
			}
			
		} catch (Exception e) {
			System.out.println("updateRealDoc() saveFile " + name +" 异常！");
			e.printStackTrace();
			rt.setMsgData(e);
			return false;
		}
		
		System.out.println("updateRealDoc() saveFile return: " + retName);
		if(retName == null  || !retName.equals(name))
		{
			System.out.println("updateRealDoc() saveFile " + name +" Failed！");
			return false;
		}
		return true;
	}
	
	private boolean checkFileSizeAndCheckSum(String localDocParentPath, String name, Integer fileSize,
			String fileCheckSum) {
		// TODO Auto-generated method stub
		File file = new File(localDocParentPath,name);
		if(fileSize != file.length())
		{
			System.out.println("checkFileSizeAndCheckSum() fileSize " + file.length() + "not match with ExpectedSize" + fileSize);
			return false;
		}
		return true;
	}

	private boolean moveRealDoc(String reposRPath, String srcParentPath, String srcName, String dstParentPath,String dstName,Integer type, ReturnAjax rt) 
	{
		System.out.println("moveRealDoc() " + " reposRPath:"+reposRPath + " srcParentPath:"+srcParentPath + " srcName:"+srcName + " dstParentPath:"+dstParentPath + " dstName:"+dstName);
		String localOldParentPath = reposRPath + srcParentPath;
		String oldFilePath = localOldParentPath+ srcName;
		String localNewParentPath = reposRPath + dstParentPath;
		String newFilePath = localNewParentPath + dstName;
		//检查orgFile是否存在
		if(isFileExist(oldFilePath) == false)
		{
			System.out.println("moveRealDoc() " + oldFilePath + " not exists");
			rt.setMsgData("moveRealDoc() " + oldFilePath + " not exists");
			return false;
		}
		
		//检查dstFile是否存在
		if(isFileExist(newFilePath) == true)
		{
			System.out.println("moveRealDoc() " + newFilePath + " already exists");
			rt.setMsgData("moveRealDoc() " + newFilePath + " already exists");
			return false;
		}
	
		/*移动文件或目录*/		
		if(moveFile(localOldParentPath,srcName,localNewParentPath,dstName,false) == false)	//强制覆盖
		{
			System.out.println("moveRealDoc() move " + oldFilePath + " to "+ newFilePath + " Failed");
			rt.setMsgData("moveRealDoc() move " + oldFilePath + " to "+ newFilePath + " Failed");
			return false;
		}
		return true;
	}
	
	private boolean copyRealDoc(String reposRPath, String srcParentPath,String srcName,String dstParentPath,String dstName, Integer type, ReturnAjax rt) {
		String srcDocPath = reposRPath + srcParentPath + srcName;
		String dstDocPath = reposRPath + dstParentPath + dstName;

		if(isFileExist(srcDocPath) == false)
		{
			System.out.println("文件: " + srcDocPath + " 不存在");
			rt.setMsgData("文件: " + srcDocPath + " 不存在");
			return false;
		}
		
		if(isFileExist(dstDocPath) == true)
		{
			System.out.println("文件: " + dstDocPath + " 已存在");
			rt.setMsgData("文件: " + dstDocPath + " 已存在");
			return false;
		}
		
		try {
			
			if(type == 2)	//如果是目录则创建目录
			{
				if(false == createDir(dstDocPath))
				{
					System.out.println("目录: " + dstDocPath + " 创建");
					rt.setMsgData("目录: " + dstDocPath + " 创建");
					return false;
				}
			}
			else	//如果是文件则复制文件
			{
				if(copyFile(srcDocPath,dstDocPath,false) == false)	//强制覆盖
				{
					System.out.println("文件: " + srcDocPath + " 复制失败");
					rt.setMsgData("文件: " + srcDocPath + " 复制失败");
					return false;
				}
			}
		} catch (IOException e) {
			System.out.println("系统异常：文件复制失败！");
			e.printStackTrace();
			rt.setMsgData(e);
			return false;
		}
		return true;
	}

	//create Virtual Doc
	private boolean createVirtualDoc(String reposVPath, String docVName,String content, ReturnAjax rt) {
		String vDocPath = reposVPath + docVName;
		System.out.println("vDocPath: " + vDocPath);
		if(isFileExist(vDocPath) == true)
		{
			System.out.println("目录 " +vDocPath + "　已存在！");
			rt.setMsgData("目录 " +vDocPath + "　已存在！");
			return false;
		}
			
		if(false == createDir(vDocPath))
		{
			System.out.println("目录 " + vDocPath + " 创建失败！");
			rt.setMsgData("目录 " + vDocPath + " 创建失败！");
			return false;
		}
		if(createDir(vDocPath + "/res") == false)
		{
			System.out.println("目录 " + vDocPath + "/res" + " 创建失败！");
			rt.setMsgData("目录 " + vDocPath + "/res" + " 创建失败！");
			return false;
		}
		if(createFile(vDocPath,"content.md") == false)
		{
			System.out.println("目录 " + vDocPath + "/content.md" + " 创建失败！");
			rt.setMsgData("目录 " + vDocPath + "/content.md" + " 创建失败！");
			return false;			
		}
		if(content !=null && !"".equals(content))
		{
			saveVirtualDocContent(reposVPath,docVName, content,rt);
		}
		
		return true;
	}
	
	private boolean deleteVirtualDoc(String reposVPath, String docVName, ReturnAjax rt) {
		String localDocVPath = reposVPath + docVName;
		if(delDir(localDocVPath) == false)
		{
			rt.setMsgData("deleteVirtualDoc() delDir失败 " + localDocVPath);
			return false;
		}
		return true;
	}
	
	private boolean moveVirtualDoc(String reposRefVPath, String srcDocVName,String dstDocVName, ReturnAjax rt) {
		if(moveFile(reposRefVPath, srcDocVName, reposRefVPath, dstDocVName, false) == false)
		{
			rt.setMsgData("moveVirtualDoc() moveFile " + " reposRefVPath:" + reposRefVPath + " srcDocVName:" + srcDocVName+ " dstDocVName:" + dstDocVName);
			return false;
		}
		return true;
	}
	
	private boolean copyVirtualDoc(String reposVPath, String srcDocVName, String dstDocVName, ReturnAjax rt) {
		String srcDocFullVPath = reposVPath + srcDocVName;
		String dstDocFullVPath = reposVPath + dstDocVName;
		if(copyFolder(srcDocFullVPath,dstDocFullVPath) == false)
		{
			rt.setMsgData("copyVirtualDoc() copyFolder " + " srcDocFullVPath:" + srcDocFullVPath +  " dstDocFullVPath:" + dstDocFullVPath );
			return false;
		}
		return true;
	}

	private boolean saveVirtualDocContent(String localParentPath, String docVName, String content, ReturnAjax rt) {
		String vDocPath = localParentPath + docVName + "/";
		File folder = new File(vDocPath);
		if(!folder.exists())
		{
			System.out.println("saveVirtualDocContent() vDocPath:" + vDocPath + " not exists!");
			if(folder.mkdir() == false)
			{
				System.out.println("saveVirtualDocContent() mkdir vDocPath:" + vDocPath + " Failed!");
				rt.setMsgData("saveVirtualDocContent() mkdir vDocPath:" + vDocPath + " Failed!");
				return false;
			}
		}
		
		//set the md file Path
		String mdFilePath = vDocPath + "content.md";
		//创建文件输入流
		FileOutputStream out = null;
		try {
			out = new FileOutputStream(mdFilePath);
		} catch (FileNotFoundException e) {
			System.out.println("saveVirtualDocContent() new FileOutputStream failed");
			e.printStackTrace();
			rt.setMsgData(e);
			return false;
		}
		try {
			out.write(content.getBytes(), 0, content.length());
			//关闭输出流
			out.close();
		} catch (IOException e) {
			System.out.println("saveVirtualDocContent() out.write exception");
			e.printStackTrace();
			rt.setMsgData(e);
			return false;
		}
		return true;
	}
	
	//Create Ref Data (File or Dir), both support Real Doc and Virtual Doc
	private boolean createRefRealDoc(String reposRPath,String reposRefRPath,String parentPath, String name, Integer type, ReturnAjax rt)
	{
		//RefDoc本意是希望提升后续文件修改后，diff部分的commit速度，但事实上是人们通常只会去修改文本文件（通常较小），所以就显得没有那么必要
		//另外，即使真的需要也将考虑只放在新增文件只会，因为新增文件可能是大批量的文件上传，速度是需要优先考虑的
		System.out.println("createRefRealDoc() now refData not used");
		return false;
		/*
		String localParentPath =  reposRPath + parentPath;
		String localRefParentPath =  reposRefRPath + parentPath;
		String localDocPath = localParentPath + name;
		String localRefDocPath = localRefParentPath + name;
		System.out.println("createRefDoc() localDocPath:" + localDocPath + " localRefDocPath:" + localRefDocPath);
		if(type == 2) //目录
		{
			if(isFileExist(localRefDocPath) == true)
			{
				System.out.println("createRefDoc() 目录 " + localRefDocPath + "　已存在！");
				rt.setMsgData("createRefDoc() 目录 " + localRefDocPath + "　已存在！");
				return false;
			}
			
			if(false == createDir(localRefDocPath))
			{
				System.out.println("createRefDoc() 目录 " +localRefDocPath + " 创建失败！");
				rt.setMsgData("createRefDoc() 目录 " +localRefDocPath + " 创建失败！");
				return false;
			}				
		}
		else
		{
			try {
				copyFile(localDocPath, localRefDocPath, true);
			} catch (IOException e) {
				System.out.println("createRefDoc() copy " + localDocPath + " to " + localRefDocPath + "Failed!");
				e.printStackTrace();
				rt.setMsgData(e);
				return false;
			}
		}
		System.out.println("createRefDoc() OK");
		return true;
		*/
	}
	
	private boolean updateRefRealDoc(String reposRPath, String reposRefRPath,
			String parentPath, String name, Integer type, ReturnAjax rt) {
		return createRefRealDoc(reposRPath, reposRefRPath, parentPath, name, type,rt);
	}
	
	private boolean createRefVirtualDoc(String reposVPath,String reposRefVPath,String vDocName, ReturnAjax rt) {
		System.out.println("createRefVirtualDoc() now refData not used");
		return false;
		/*System.out.println("createRefVirtualDoc() reposVPath:" + reposVPath + " reposRefVPath:" + reposRefVPath + " vDocName:" + vDocName);
		
		String localPath = reposVPath + vDocName;
		String localRefPath = reposRefVPath + vDocName;
		
		if(isFileExist(localRefPath) == true)
		{
			System.out.println("createRefVirtualDoc() " +localRefPath + " 已存在！");
			rt.setMsgData("createRefVirtualDoc() " +localRefPath + " 已存在！");
			return false;
		}
		
		return copyFolder(localPath, localRefPath);
		*/
	}
	
	private Integer getMaxFileSize() {
		// TODO Auto-generated method stub
		return null;
	}

	private boolean isNodeExist(String name, Integer parentId, Integer reposId) {
		Doc qdoc = new Doc();
		qdoc.setName(name);
		qdoc.setPid(parentId);
		qdoc.setVid(reposId);
		List <Doc> docList = reposService.getDocList(qdoc);
		if(docList != null && docList.size() > 0)
		{
			return true;
		}
		return false;
	}
	
	Doc getDocByName(String name, Integer parentId, Integer reposId)
	{
		Doc qdoc = new Doc();
		qdoc.setName(name);
		qdoc.setPid(parentId);
		qdoc.setVid(reposId);
		List <Doc> docList = reposService.getDocList(qdoc);
		if(docList != null && docList.size() > 0)
		{
			return docList.get(0);
		}
		return null;
	}

	//0：虚拟文件系统  1：实文件系统 
	boolean isRealFS(Integer type)
	{
		if(type == 0)
		{
			return false;
		}
		return true;
	}
	
	/********************* Functions For User Opertion Right****************************/
	//检查用户的新增权限
	private boolean checkUserAddRight(ReturnAjax rt, Integer userId,
			Integer parentId, Integer reposId) {
		DocAuth docUserAuth = getUserDocAuth(userId,parentId,reposId);
		if(docUserAuth == null)
		{
			rt.setError("您无此操作权限，请联系管理员");
			return false;
		}
		else
		{
			if(docUserAuth.getAccess() == 0)
			{
				rt.setError("您无权访问该目录，请联系管理员");
				return false;
			}
			else if(docUserAuth.getAddEn() != 1)
			{
				rt.setError("您没有该目录的新增权限，请联系管理员");
				return false;				
			}
		}
		return true;
	}

	private boolean checkUserDeleteRight(ReturnAjax rt, Integer userId,
			Integer parentId, Integer reposId) {
		DocAuth docUserAuth = getUserDocAuth(userId,parentId,reposId);
		if(docUserAuth == null)
		{
			rt.setError("您无此操作权限，请联系管理员");
			return false;
		}
		else
		{
			if(docUserAuth.getAccess() == 0)
			{
				rt.setError("您无权访问该目录，请联系管理员");
				return false;
			}
			else if(docUserAuth.getDeleteEn() != 1)
			{
				rt.setError("您没有该目录的删除权限，请联系管理员");
				return false;				
			}
		}
		return true;
	}
	
	private boolean checkUserEditRight(ReturnAjax rt, Integer userId, Integer docId,
			Integer reposId) {
		DocAuth docUserAuth = getUserDocAuth(userId,docId,reposId);
		if(docUserAuth == null)
		{
			rt.setError("您无此操作权限，请联系管理员");
			return false;
		}
		else
		{
			if(docUserAuth.getAccess() == 0)
			{
				rt.setError("您无权访问该文件，请联系管理员");
				return false;
			}
			else if(docUserAuth.getEditEn() != 1)
			{
				rt.setError("您没有该文件的编辑权限，请联系管理员");
				return false;				
			}
		}
		return true;
	}
	
	private boolean checkUseAccessRight(ReturnAjax rt, Integer userId, Integer docId,
			Integer reposId) {
		DocAuth docAuth = getUserDocAuth(userId,docId,reposId);
		if(docAuth == null)
		{
			rt.setError("您无此操作权限，请联系管理员");
			return false;
		}
		else
		{
			Integer access = docAuth.getAccess();
			if(access == null || access.equals(0))
			{
				rt.setError("您无权访问该文件，请联系管理员");
				return false;
			}
		}
		return true;
	}
	/*************** Functions For SVN *********************/
	private List<LogEntry> svnGetHistory(Repos repos,String docPath) {

		SVNUtil svnUtil = new SVNUtil();
		svnUtil.Init(repos.getSvnPath(), repos.getSvnUser(), repos.getSvnPwd());
		return svnUtil.getHistoryLogs(docPath, 0, -1);
	}
	
	private boolean svnRealDocAdd(Repos repos, String parentPath,String entryName,Integer type,String commitMsg, String commitUser, ReturnAjax rt) 
	{
		String remotePath = parentPath + entryName;
		String reposRPath = getReposRealPath(repos);
		String reposRefRPath = getReposRefRealPath(repos);
		if(repos.getVerCtrl() == 1)
		{
			String reposURL = repos.getSvnPath();
			String svnUser = repos.getSvnUser();
			if(svnUser==null || "".equals(svnUser))
			{
				svnUser = commitUser;
			}
			String svnPwd = repos.getSvnPwd();
	
			try {
				SVNUtil svnUtil = new SVNUtil();
				svnUtil.Init(reposURL, svnUser, svnPwd);
				
				if(svnUtil.doCheckPath(remotePath, -1) == false)	//检查文件是否已经存在于仓库中
				{
					if(type == 1)
					{
						String localFilePath = reposRPath + remotePath;
						if(svnUtil.svnAddFile(parentPath,entryName,localFilePath,commitMsg) == false)
						{
							System.out.println("svnRealDocAdd() " + remotePath + " svnUtil.svnAddFile失败！");	
							rt.setMsgData("svnRealDocAdd() " + remotePath + " svnUtil.svnAddFile失败！");	
							return false;
						}
					}
					else
					{
						if(svnUtil.svnAddDir(parentPath,entryName,commitMsg) == false)
						{
							System.out.println("svnRealDocAdd() " + remotePath + " svnUtil.svnAddDir失败！");	
							rt.setMsgData("svnRealDocAdd() " + remotePath + " svnUtil.svnAddDir失败！");
							return false;
						}
					}
				}
				else	//如果已经存在，则只是将修改的内容commit到服务器上
				{
					System.out.println(remotePath + "在仓库中已存在！");
					rt.setMsgData("svnRealDocAdd() " + remotePath + "在仓库中已存在！");
					return false;
				}
			} catch (SVNException e) {
				e.printStackTrace();
				System.out.println("系统异常：" + remotePath + " svnRealDocAdd异常！");
				rt.setMsgData(e);
				return false;
			}
			
			//Create the ref real doc, so that we can commit the diff later
			createRefRealDoc(reposRPath,reposRefRPath,parentPath,entryName,type,rt);
			return true;
		}
		else
		{
			return true;
		}
	}
	
	private boolean svnRealDocDelete(Repos repos, String parentPath, String name,Integer type,
			String commitMsg, String commitUser, ReturnAjax rt) {
		System.out.println("svnRealDocDelete() parentPath:" + parentPath + " name:" + name);
		if(repos.getVerCtrl() == 1)
		{
		
			String reposURL = repos.getSvnPath();
			String svnUser = repos.getSvnUser();
			if(svnUser==null || "".equals(svnUser))
			{
				svnUser = commitUser;
			}
			String svnPwd = repos.getSvnPwd();
			String docRPath = parentPath + name;
			try {
				SVNUtil svnUtil = new SVNUtil();
				svnUtil.Init(reposURL, svnUser, svnPwd);
				if(svnUtil.doCheckPath(docRPath,-1) == true)	//如果仓库中该文件已经不存在，则不需要进行svnDeleteCommit
				{
					if(svnUtil.svnDelete(parentPath,name,commitMsg) == false)
					{
						System.out.println(docRPath + " remoteDeleteEntry失败！");
						rt.setMsgData("svnRealDocDelete() svnUtil.svnDelete失败" + " docRPath:" + docRPath + " name:" + name);
						return false;
					}
				}
			} catch (SVNException e) {
				System.out.println("系统异常：" + docRPath + " remoteDeleteEntry异常！");
				e.printStackTrace();
				rt.setMsgData(e);
				return false;
			}
			
			//delete the ref real doc
			String reposRefRPath = getReposRefRealPath(repos);
			deleteRealDoc(reposRefRPath,parentPath,name,type,rt);
			return true;
		}
		else
		{
			return true;
		}
	}

	private boolean svnRealDocCommit(Repos repos, String parentPath,
			String name,Integer type, String commitMsg, String commitUser, ReturnAjax rt) {
		
		System.out.println("svnRealDocCommit() parentPath:" + parentPath + " name:" + name);
		if(repos.getVerCtrl() == 1)
		{
			String reposURL = repos.getSvnPath();
			String svnUser = repos.getSvnUser();
			if(svnUser==null || "".equals(svnUser))
			{
				svnUser = commitUser;
			}
			String svnPwd = repos.getSvnPwd();
			String reposRPath =  getReposRealPath(repos);
			String docRPath = parentPath + name;
			String docFullRPath = reposRPath + parentPath + name;
			String newFilePath = docFullRPath;
			
			try {
				SVNUtil svnUtil = new SVNUtil();
				svnUtil.Init(reposURL, svnUser, svnPwd);
				
				if(svnUtil.doCheckPath(docRPath, -1) == false)	//检查文件是否已经存在于仓库中
				{					
					System.out.println("svnRealDocCommit() " + docRPath + " 在仓库中不存在！");
					if(false == svnUtil.svnAddFile(parentPath,name,newFilePath,commitMsg))
					{
						System.out.println("svnRealDocCommit() " + name + " svnAddFile失败！");
						System.out.println("svnRealDocCommit() svnUtil.svnAddFile " + " parentPath:" + parentPath  + " name:" + name  + " newFilePath:" + newFilePath);
						return false;
					}
				}
				else	//如果已经存在，则只是将修改的内容commit到服务器上
				{
					String oldFilePath = getReposRefRealPath(repos) + docRPath;
					if(svnUtil.svnModifyFile(parentPath,name,oldFilePath, newFilePath, commitMsg) == false)
					{
						System.out.println("svnRealDocCommit() " + name + " remoteModifyFile失败！");
						System.out.println("svnRealDocCommit() svnUtil.svnModifyFile " + " parentPath:" + parentPath  + " name:" + name  + " oldFilePath:" + oldFilePath + " newFilePath:" + newFilePath);
						return false;
					}
				}
			} catch (SVNException e) {
				System.out.println("svnRealDocCommit() 系统异常：" + name + " svnRealDocCommit异常！");
				e.printStackTrace();
				rt.setMsgData(e);
				return false;
			}
			
			//Update the RefRealDoc with the RealDoc
			String reposRefRPath = getReposRefRealPath(repos);
			updateRefRealDoc(reposRPath,reposRefRPath,parentPath,name,type,rt);
			return true;
		}
		else
		{
			return true;
		}
			
	}

	private boolean svnRealDocMove(Repos repos, String srcParentPath,String srcEntryName,
			String dstParentPath, String dstEntryName,Integer type, String commitMsg, String commitUser, ReturnAjax rt) {
		
		System.out.println("svnRealDocMove() srcParentPath:" + srcParentPath + " srcEntryName:" + srcEntryName + " dstParentPath:" + dstParentPath + " dstEntryName:" + dstEntryName);
		String reposRefRPath = getReposRefRealPath(repos);
		if(repos.getVerCtrl() == 1)
		{	
			String reposURL = repos.getSvnPath();
			String svnUser = repos.getSvnUser();
			if(svnUser==null || "".equals(svnUser))
			{
				svnUser = commitUser;
			}
			String svnPwd = repos.getSvnPwd();
			if(svnMove(reposURL,svnUser,svnPwd,srcParentPath,srcEntryName,dstParentPath,dstEntryName,commitMsg) == false)
			{
				System.out.println("svnMove Failed！");
				rt.setMsgData("svnMove Failed！");
				return false;
			}
			
			//rename the ref real doc
			moveRealDoc(reposRefRPath,srcParentPath,srcEntryName,dstParentPath,dstEntryName,type,rt);
			return true;
		}
		else
		{
			System.out.println("svnRealDocMove() verCtrl " + repos.getVerCtrl());
			return true;
		}
	}

	private boolean svnRealDocCopy(Repos repos, String srcParentPath, String srcEntryName,
			String dstParentPath, String dstEntryName, Integer type, String commitMsg, String commitUser, ReturnAjax rt) {
		
		System.out.println("svnRealDocCopy() srcParentPath:" + srcParentPath + " srcEntryName:" + srcEntryName + " dstParentPath:" + dstParentPath + " dstEntryName:" + dstEntryName);
		if(repos.getVerCtrl() == 1)
		{				
		
			String reposURL = repos.getSvnPath();
			String svnUser = repos.getSvnUser();
			if(svnUser==null || "".equals(svnUser))
			{
				svnUser = commitUser;
			}
			String svnPwd = repos.getSvnPwd();
			if(svnCopy(reposURL,svnUser,svnPwd,srcParentPath,srcEntryName,dstParentPath,dstEntryName,commitMsg,rt) == false)
			{
				System.out.println("文件: " + srcEntryName + " svnCopy失败");
				return false;
			}
			
			//create Ref RealDoc
			String reposRPath = getReposRealPath(repos);
			String reposRefRPath = getReposRefRealPath(repos);
			createRefRealDoc(reposRPath, reposRefRPath, dstParentPath, dstEntryName, type,rt);
			return true;
		}
		else
		{
			return true;
		}
	}
	
	private boolean svnVirtualDocAdd(Repos repos, String docVName,String commitMsg, String commitUser, ReturnAjax rt) {
		
		System.out.println("svnVirtualDocAdd() docVName:" + docVName);
		
		if(repos.getVerCtrl1() == 1)
		{
			String reposURL = repos.getSvnPath1();
			String svnUser = repos.getSvnUser1();
			String svnPwd = repos.getSvnPwd1();
			SVNUtil svnUtil = new SVNUtil();
			if(svnUtil.Init(reposURL, svnUser, svnPwd) == false)
			{
				System.out.println("svnVirtualDocAdd() svnUtil Init Failed!");
				rt.setMsgData("svnVirtualDocAdd() svnUtil Init Failed!");
				return false;
			}
			
			String reposVPath =  getReposVirtualPath(repos);
			String reposRefVPath = getReposRefVirtualPath(repos);
			
			//modifyEnable set to false
			if(svnUtil.doAutoCommit("",docVName,reposVPath,commitMsg,false,reposRefVPath) == false)
			{
				System.out.println(docVName + " doAutoCommit失败！");
				rt.setMsgData("doAutoCommit失败！" + " docVName:" + docVName + " reposVPath:" + reposVPath + " reposRefVPath:" + reposRefVPath );
				return false;
			}
			
			//同步两个目录,modifyEnable set to false
			createRefVirtualDoc(reposVPath,reposRefVPath,docVName,rt);
			return true;
		}
		else
		{
			return true;
		}
	}
	
	private boolean svnVirtualDocDelete(Repos repos, String docVName, String commitMsg, String commitUser, ReturnAjax rt) {
		System.out.println("svnVirtualDocDelete() docVName:" + docVName);
		if(repos.getVerCtrl1() == 1)
		{
		
			String reposURL = repos.getSvnPath1();
			String svnUser = repos.getSvnUser1();
			if(svnUser==null || "".equals(svnUser))
			{
				svnUser = commitUser;
			}
			String svnPwd = repos.getSvnPwd1();
			
			try {
				SVNUtil svnUtil = new SVNUtil();
				svnUtil.Init(reposURL, svnUser, svnPwd);
				if(svnUtil.doCheckPath(docVName,-1) == true)	//如果仓库中该文件已经不存在，则不需要进行svnDeleteCommit
				{
					if(svnUtil.svnDelete("",docVName,commitMsg) == false)
					{
						System.out.println(docVName + " remoteDeleteEntry失败！");
						rt.setMsgData("svnVirtualDocDelete() svnUtil.svnDelete "  + docVName +" 失败 ");
						return false;
					}
				}
			} catch (SVNException e) {
				e.printStackTrace();
				System.out.println("系统异常：" + docVName + " remoteDeleteEntry异常！");
				rt.setMsgData(e);
				return false;
			}
			
			//delete Ref Virtual Doc
			String reposRefVPath = getReposRefVirtualPath(repos);
			deleteVirtualDoc(reposRefVPath,docVName,rt);
			return true;
		}
		else
		{
			return true;
		}
	}

	private boolean svnVirtualDocCommit(Repos repos, String docVName,String commitMsg, String commitUser, ReturnAjax rt) {
		System.out.println("svnVirtualDocCommit() docVName:" + docVName);
		if(repos.getVerCtrl1() == 1)
		{
			String reposURL = repos.getSvnPath1();
			String svnUser = repos.getSvnUser1();
			String svnPwd = repos.getSvnPwd1();
			String reposVPath =  getReposVirtualPath(repos);
			
			SVNUtil svnUtil = new SVNUtil();
			svnUtil.Init(reposURL, svnUser, svnPwd);
				
			if(commitMsg == null || "".equals(commitMsg))
			{
				commitMsg = "Commit virtual doc " + docVName + " by " + commitUser;
			}
			
			String reposRefVPath = getReposRefVirtualPath(repos);
			if(svnUtil.doAutoCommit("",docVName,reposVPath,commitMsg,true,reposRefVPath) == false)
			{
				System.out.println(docVName + " doCommit失败！");
				rt.setMsgData(" doCommit失败！" + " docVName:" + docVName + " reposVPath:" + reposVPath + " reposRefVPath:" + reposRefVPath);
				return false;
			}
			
			//创建RefVDoc
			//syncUpFolder(reposVPath,docVName,reposRefVPath,docVName,true);
			return true;
		}
		else
		{
			return true;
		}
	}

	private boolean svnVirtualDocMove(Repos repos, String srcDocVName,String dstDocVName, String commitMsg, String commitUser, ReturnAjax rt) {
		System.out.println("svnVirtualDocMove() srcDocVName:" + srcDocVName + " dstDocVName:" + dstDocVName);
		if(repos.getVerCtrl1() == 1)
		{	
			String reposURL = repos.getSvnPath1();
			String svnUser = repos.getSvnUser1();
			if(svnUser==null || "".equals(svnUser))
			{
				svnUser = commitUser;
			}
			String svnPwd = repos.getSvnPwd1();
			if(svnMove(reposURL,svnUser,svnPwd,"",srcDocVName,"",dstDocVName,commitMsg) == false)
			{
				System.out.println("svnMove Failed！");
				rt.setMsgData("svnVirtualDocMove() svnMove Failed！");
				return false;
			}
			
			//move the ref virtual doc
			String reposRefVPath = getReposRefVirtualPath(repos);
			moveVirtualDoc(reposRefVPath, srcDocVName, dstDocVName,rt);
			return true;
		}
		else
		{
			System.out.println("svnRealDocMove() verCtrl " + repos.getVerCtrl());
			return true;
		}
	}

	private boolean svnVirtualDocCopy(Repos repos,String srcDocVName,String dstDocVName,String commitMsg, String commitUser, ReturnAjax rt) {

		System.out.println("svnVirtualDocCopy() srcDocVName:" + srcDocVName + " dstDocVName:" + dstDocVName);
		if(repos.getVerCtrl1() == 1)
		{				
			String reposURL = repos.getSvnPath1();
			String svnUser = repos.getSvnUser1();
			if(svnUser==null || "".equals(svnUser))
			{
				svnUser = commitUser;
			}
			String svnPwd = repos.getSvnPwd1();
			if(svnCopy(reposURL,svnUser,svnPwd,"",srcDocVName,"",dstDocVName,commitMsg,rt) == false)
			{
				System.out.println("文件: " + srcDocVName + " svnCopy失败");
				return false;
			}
			
			//create Ref Virtual Doc
			String reposVPath = getReposVirtualPath(repos);
			String reposRefVPath = getReposRefVirtualPath(repos);
			createRefVirtualDoc(reposVPath,reposRefVPath,dstDocVName,rt);
			return true;
		}
		else
		{
			return true;
		}
	}

	private boolean svnRevertRealDoc(Repos repos, String parentPath,String entryName, Integer type, ReturnAjax rt) 
	{
		System.out.println("svnRevertRealDoc() parentPath:" + parentPath + " entryName:" + entryName);
		String localParentPath = getReposRealPath(repos) + parentPath;

		if(type == 2) //如果是目录则重新新建目录即可
		{
			File file = new File(localParentPath,entryName);
			return file.mkdir();
		}
		
		//revert from svn server
		String reposURL = repos.getSvnPath();
		String svnUser = repos.getSvnUser();
		String svnPwd = repos.getSvnPwd();
		return svnRevert(reposURL, svnUser, svnPwd, parentPath, entryName, localParentPath, entryName);
	}
	

	private boolean svnRevertVirtualDoc(Repos repos, String docVName) {
		System.out.println("svnRevertVirtualDoc() docVName:" + docVName);
		
		String localDocVParentPath = getReposVirtualPath(repos);

		//getFolder From the version DataBase
		String reposURL = repos.getSvnPath1();
		String svnUser = repos.getSvnUser1();
		String svnPwd = repos.getSvnPwd1();
		return svnCheckOut(reposURL, svnUser, svnPwd, "", docVName, localDocVParentPath, docVName,-1);
	}
	
	private int svnGetEntryType(String reposURL, String svnUser, String svnPwd, String parentPath,String entryName, long revision) 
	{
		System.out.println("svnGetEntryType() parentPath:" + parentPath + " entryName:" + entryName);
		
		SVNUtil svnUtil = new SVNUtil();
		if(svnUtil.Init(reposURL, svnUser, svnPwd) == false)
		{
			System.out.println("svnGetEntryType() svnUtil Init Failed: " + reposURL);
			return -1;
		}
		
		String remoteEntryPath = parentPath + entryName;
		int entryType = svnUtil.getEntryType(remoteEntryPath, revision);
		
		return entryType;
	}
	
	private boolean svnCheckOut(String reposURL, String svnUser, String svnPwd, String parentPath,String entryName, String localParentPath,String targetName,long revision) 
	{
		System.out.println("svnCheckOut() parentPath:" + parentPath + " entryName:" + entryName + " localParentPath:" + localParentPath);
		
		SVNUtil svnUtil = new SVNUtil();
		if(svnUtil.Init(reposURL, svnUser, svnPwd) == false)
		{
			System.out.println("svnCheckOut() svnUtil Init Failed: " + reposURL);
			return false;
		}
		
		return svnGetEntry(svnUtil, parentPath, entryName, localParentPath, targetName, revision);
	}
	
	//getFile or directory from VersionDB
	private boolean svnGetEntry(SVNUtil svnUtil, String parentPath, String entryName, String localParentPath,String targetName,long revision) 
	{
		System.out.println("svnGetEntry() parentPath:" + parentPath + " entryName:" + entryName + " localParentPath:" + localParentPath + " targetName:" + targetName);
		
		//check targetName and set
		if(targetName == null)
		{
			targetName = entryName;
		}
		
		String remoteEntryPath = parentPath + entryName;
		int entryType = svnUtil.getEntryType(remoteEntryPath, revision);
		if(entryType == 1)	//File
		{
			svnUtil.getFile(localParentPath + targetName,parentPath,entryName,revision);				
		}
		else if(entryType == 2)
		{
			File dir = new File(localParentPath,targetName);
			dir.mkdir();
			
			//Get the subEntries and call svnGetEntry
			String localEntryPath = localParentPath + targetName + "/";
			List <SVNDirEntry> subEntries = svnUtil.getSubEntries(remoteEntryPath);
			for(int i=0;i<subEntries.size();i++)
			{
				SVNDirEntry subEntry =subEntries.get(i);
				String subEntryName = subEntry.getName();
				if(svnGetEntry(svnUtil,remoteEntryPath+"/",subEntryName,localEntryPath,null,revision) == false)
				{
					System.out.println("svnGetEntry() svnGetEntry Failed: " + subEntryName);
					return false;
				}
			}
		}
		else if(entryType == 0)
		{
			//System.out.println("svnGetEntry() " + remoteEntryPath + " 在仓库中不存在！");
		}
		else	//如果已经存在，则只是将修改的内容commit到服务器上
		{
			System.out.println("svnGetEntry() " + remoteEntryPath + " 是未知类型！");
			return false;
		}
	
		return true;
	}

	//svnRevert: only for file
	private boolean svnRevert(String reposURL, String svnUser, String svnPwd, String parentPath,String entryName,String localParentPath,String localEntryName) 
	{

		SVNUtil svnUtil = new SVNUtil();
		if(svnUtil.Init(reposURL, svnUser, svnPwd) == false)
		{
			System.out.println("svnRevert() svnUtil Init Failed: " + reposURL);
			return false;
		}
		
		String remoteEntryPath = parentPath + entryName;
		String localEntryPath = localParentPath + localEntryName;
		try {
			if(svnUtil.doCheckPath(remoteEntryPath, -1) == false)	//检查文件是否已经存在于仓库中
			{
				System.out.println(remoteEntryPath + " 在仓库中不存在！");
				return false;
			}
			else //getFile From the Version DataBase
			{
				svnUtil.getFile(localEntryPath+entryName,parentPath,entryName,-1);
			}
		} catch (SVNException e) {
			System.out.println("svnRevert() revertFile " + localEntryPath + " Failed!");
			e.printStackTrace();
			return false;
		}
	
		return true;
	}
	
	private boolean svnCopy(String reposURL, String svnUser, String svnPwd,
			String srcParentPath, String srcEntryName, String dstParentPath,String dstEntryName,
			String commitMsg, ReturnAjax rt) 
	{
		SVNUtil svnUtil = new SVNUtil();
		svnUtil.Init(reposURL, svnUser, svnPwd);
		
		if(svnUtil.svnCopy(srcParentPath, srcEntryName, dstParentPath, dstEntryName, commitMsg, false) == false)
		{
			rt.setMsgData("svnCopy() svnUtil.svnCopy " + " srcParentPath:" + srcParentPath + " srcEntryName:" + srcEntryName + " dstParentPath:" + dstParentPath+ " dstEntryName:" + dstEntryName);
			return false;
		}
		return true;
	}
	
	private boolean svnMove(String reposURL, String svnUser, String svnPwd,
			String srcParentPath,String srcEntryName, String dstParentPath,String dstEntryName,
			String commitMsg)  
	{
		SVNUtil svnUtil = new SVNUtil();
		svnUtil.Init(reposURL, svnUser, svnPwd);
		
		if(svnUtil.svnCopy(srcParentPath, srcEntryName, dstParentPath,dstEntryName, commitMsg, true) == false)
		{
			return false;
		}
		return true;
	}
	
}
	